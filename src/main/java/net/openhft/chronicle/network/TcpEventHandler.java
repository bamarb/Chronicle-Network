/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.network;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesUtil;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.PackageLocal;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.tcp.ISocketChannel;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.network.api.TcpHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static net.openhft.chronicle.network.connection.TcpChannelHub.TCP_BUFFER;

public class TcpEventHandler<T extends NetworkContext<T>> implements EventHandler, Closeable, TcpEventHandlerManager<T> {

    private static final int MONITOR_POLL_EVERY_SEC = Integer.getInteger("tcp.event.monitor.secs", 10);
    private static final long NBR_WARNING_NANOS = Long.getLong("tcp.nbr.warning.nanos", 2_000_000);
    private static final long NBW_WARNING_NANOS = Long.getLong("tcp.nbw.warning.nanos", 2_000_000);
    private static final Logger LOG = LoggerFactory.getLogger(TcpEventHandler.class);
    private static final AtomicBoolean FIRST_HANDLER = new AtomicBoolean();
    public static final int TARGET_WRITE_SIZE = Integer.getInteger("TcpEventHandler.targetWriteSize", 1024);
    public static boolean DISABLE_TCP_NODELAY = Boolean.getBoolean("disable.tcp_nodelay");


    static {
        if (DISABLE_TCP_NODELAY) System.out.println("tcpNoDelay disabled");
    }

    @NotNull
    private final ISocketChannel sc;
    private final String scToString;
    @NotNull
    private final T nc;
    @NotNull
    private final NetworkLog readLog, writeLog;
    @NotNull
    private final Bytes<ByteBuffer> inBBB;
    @NotNull
    private final Bytes<ByteBuffer> outBBB;
    private final boolean fair;
    @NotNull
    private final AtomicBoolean scClosed = new AtomicBoolean();
    @NotNull
    private final AtomicBoolean closed = new AtomicBoolean();
    private int oneInTen;
    @Nullable
    private volatile TcpHandler<T> tcpHandler;
    private long lastTickReadTime = System.currentTimeMillis();

    // monitoring
    private int socketPollCount;
    private long bytesReadCount;
    private long bytesWriteCount;
    private long lastMonitor;

    public TcpEventHandler(@NotNull T nc) {
        this(nc, false);
    }

    public TcpEventHandler(@NotNull T nc, boolean fair) {

        this.sc = ISocketChannel.wrapUnsafe(nc.socketChannel());
        this.scToString = sc.toString();
        this.nc = nc;
        this.fair = fair;

        try {
            sc.configureBlocking(false);
            Socket sock = sc.socket();
            // TODO: should have a strategy for this like ConnectionNotifier
            if (!DISABLE_TCP_NODELAY)
                sock.setTcpNoDelay(true);

            if (TCP_BUFFER >= 64 << 10) {
                sock.setReceiveBufferSize(TCP_BUFFER);
                sock.setSendBufferSize(TCP_BUFFER);

                checkBufSize(sock.getReceiveBufferSize(), "recv");
                checkBufSize(sock.getSendBufferSize(), "send");
            }
        } catch (IOException e) {
            if (closed.get() || !sc.isOpen())
                throw new IORuntimeException(e);
            Jvm.warn().on(getClass(), e);
        }

        inBBB = Bytes.elasticByteBuffer(TCP_BUFFER + OS.pageSize());
        outBBB = Bytes.elasticByteBuffer(TCP_BUFFER);
        // TODO Fix Chronicle-Queue-Enterprise tests so socket connections are closed cleanly.
        BytesUtil.unregister(inBBB);
        BytesUtil.unregister(outBBB);

        // must be set after we take a slice();
        outBBB.underlyingObject().limit(0);
        readLog = new NetworkLog(this.sc, "read");
        writeLog = new NetworkLog(this.sc, "write");
        if (FIRST_HANDLER.compareAndSet(false, true))
            warmUp();
    }

    @Override
    public String toString() {
        return "TcpEventHandler{" +
                "sc=" + scToString + ", " +
                "tcpHandler=" + tcpHandler + ", " +
                "closed=" + closed +
                '}';
    }

    public void warmUp() {
        System.out.println(TcpEventHandler.class.getSimpleName() + " - Warming up...");
        int runs = 12000;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            inBBB.readPositionRemaining(8, 1024);
            compactBuffer();
            clearBuffer();
        }
        long time = System.nanoTime() - start;
        System.out.println(TcpEventHandler.class.getSimpleName() + " - ... warmed up - took " + (time / runs / 1e3) + " us avg");
    }

    private void checkBufSize(int bufSize, String name) {
        if (bufSize < TCP_BUFFER) {
            LOG.warn("Attempted to set " + name + " tcp buffer to " + TCP_BUFFER + " but kernel only allowed " + bufSize);
        }
    }

    public ISocketChannel socketChannel() {
        return sc;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @NotNull
    @Override
    public HandlerPriority priority() {
        ServerThreadingStrategy sts = nc.serverThreadingStrategy();
        switch (sts) {
            case SINGLE_THREADED:
                return singleThreadedPriority();
            case CONCURRENT:
                return HandlerPriority.CONCURRENT;
            default:
                throw new UnsupportedOperationException("todo");
        }
    }

    @NotNull
    public HandlerPriority singleThreadedPriority() {
        return HandlerPriority.MEDIUM;
    }

    @Nullable
    public TcpHandler<T> tcpHandler() {
        return tcpHandler;
    }

    @Override
    public void tcpHandler(TcpHandler<T> tcpHandler) {
        nc.onHandlerChanged(tcpHandler);
        this.tcpHandler = tcpHandler;
    }

    @Override
    public synchronized boolean action() throws InvalidEventHandlerException {
        Jvm.optionalSafepoint();

        if (closed.get())
            throw new InvalidEventHandlerException();

        if (tcpHandler == null)
            return false;

        if (!sc.isOpen()) {
            tcpHandler.onEndOfConnection(false);
            Closeable.closeQuietly(nc);
            throw new InvalidEventHandlerException("socket is closed");
        }

        socketPollCount++;

        boolean busy = false;
        if (fair || oneInTen++ >= 8) {
            oneInTen = 0;
            try {
                busy = writeAction();
            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        try {
            ByteBuffer inBB = inBBB.underlyingObject();
            int start = inBB.position();

            assert !sc.isBlocking();
            long time0 = System.nanoTime();
            int read = inBB.remaining() > 0 ? sc.read(inBB) : Integer.MAX_VALUE;
            long time1 = System.nanoTime() - time0;
            if (time1 > NBR_WARNING_NANOS)
                Jvm.warn().on(getClass(), "Non blocking read took " + time1 / 1000 + " us.");

            if (read == Integer.MAX_VALUE)
                onInBBFul();
            if (read > 0) {
                WanSimulator.dataRead(read);
                tcpHandler.onReadTime(System.nanoTime(), inBB, start, inBB.position());
                lastTickReadTime = System.currentTimeMillis();
                readLog.log(inBB, start, inBB.position());
                if (invokeHandler())
                    oneInTen++;
                busy = true;
            } else if (read == 0) {
                if (outBBB.readRemaining() > 0) {
                    busy |= invokeHandler();
                }

                // check for timeout only here - in other branches we either just read something or are about to close socket anyway
                if (nc.heartbeatTimeoutMs() > 0) {
                    long tickTime = System.currentTimeMillis();
                    if (tickTime > lastTickReadTime + nc.heartbeatTimeoutMs()) {
                        final HeartbeatListener heartbeatListener = nc.heartbeatListener();
                        if (heartbeatListener != null && heartbeatListener.onMissedHeartbeat()) {
                            // implementer tries to recover - do not disconnect for some time
                            lastTickReadTime += heartbeatListener.lingerTimeBeforeDisconnect();
                        } else {
                            tcpHandler.onEndOfConnection(true);
                            closeSC();
                            throw new InvalidEventHandlerException("heartbeat timeout");
                        }
                    }
                }

                if (!busy)
                    monitorStats();
            } else {
                // read == -1, socketChannel has reached end-of-stream
                close();
                throw new InvalidEventHandlerException("socket closed " + sc);
            }

            return busy;

        } catch (ClosedChannelException e) {
            close();
            throw new InvalidEventHandlerException(e);
        } catch (IOException e) {
            close();
            handleIOE(e, tcpHandler.hasClientClosed(), nc.heartbeatListener());
            throw new InvalidEventHandlerException();
        } catch (InvalidEventHandlerException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            Jvm.warn().on(getClass(), "", e);
            throw new InvalidEventHandlerException(e);
        }
    }

    @Override
    public void loopFinished() {
        // Release unless already released
        if (inBBB.refCount() > 0)
            inBBB.release();
        if (outBBB.refCount() > 0)
        outBBB.release();
    }

    public void onInBBFul() {
        LOG.trace("inBB is full, can't read from socketChannel");
    }

    private void monitorStats() {
        // TODO: consider installing this on EventLoop using Timer
        long now = System.currentTimeMillis();
        if (now > lastMonitor + (MONITOR_POLL_EVERY_SEC * 1000)) {
            final NetworkStatsListener<T> networkStatsListener = nc.networkStatsListener();
            if (networkStatsListener != null) {
                if (lastMonitor == 0) {
                    networkStatsListener.onNetworkStats(0, 0, 0);
                } else {
                    networkStatsListener.onNetworkStats(
                            bytesWriteCount / MONITOR_POLL_EVERY_SEC,
                            bytesReadCount / MONITOR_POLL_EVERY_SEC,
                            socketPollCount / MONITOR_POLL_EVERY_SEC);
                    bytesWriteCount = bytesReadCount = socketPollCount = 0;
                }
            }
            lastMonitor = now;
        }
    }

    @PackageLocal
    boolean invokeHandler() throws IOException {
        Jvm.optionalSafepoint();
        boolean busy = false;
        final int position = inBBB.underlyingObject().position();
        inBBB.readLimit(position);
        outBBB.writePosition(outBBB.underlyingObject().limit());

        long lastInBBBReadPosition;
        do {
            lastInBBBReadPosition = inBBB.readPosition();
            tcpHandler.process(inBBB, outBBB, nc);
            bytesReadCount += (inBBB.readPosition() - lastInBBBReadPosition);

            // process method might change the underlying ByteBuffer by resizing it.
            ByteBuffer outBB = outBBB.underlyingObject();
            // did it write something?
            int wpBBB = Maths.toUInt31(outBBB.writePosition());
            int length = wpBBB - outBB.limit();
            if (length >= TARGET_WRITE_SIZE) {
                outBB.limit(wpBBB);
                boolean busy2 = tryWrite(outBB);
                if (busy2)
                    busy = busy2;
                else
                    break;
            }
        } while (lastInBBBReadPosition != inBBB.readPosition());

        ByteBuffer outBB = outBBB.underlyingObject();
        if (outBBB.writePosition() > outBB.limit() || outBBB.writePosition() >= 4) {
            outBB.limit(Maths.toInt32(outBBB.writePosition()));
            busy |= tryWrite(outBB);
        }

        Jvm.optionalSafepoint();

        if (inBBB.readRemaining() == 0) {
            clearBuffer();

        } else if (inBBB.readPosition() > TCP_BUFFER / 4) {
            compactBuffer();

            busy = true;
        }

        return busy;
    }

    private void clearBuffer() {
        inBBB.clear();
        @Nullable ByteBuffer inBB = inBBB.underlyingObject();
        inBB.clear();
    }

    private void compactBuffer() {
        // if it read some data compact();
        @Nullable ByteBuffer inBB = inBBB.underlyingObject();
        inBB.position((int) inBBB.readPosition());
        inBB.limit((int) inBBB.readLimit());
        Jvm.optionalSafepoint();

        inBB.compact();
        Jvm.optionalSafepoint();
        inBBB.readPosition(0);
        inBBB.readLimit(inBB.remaining());
    }

    private void handleIOE(@NotNull IOException e, final boolean clientIntentionallyClosed,
                           @Nullable HeartbeatListener heartbeatListener) {
        try {

            if (clientIntentionallyClosed)
                return;
            if (e.getMessage() != null && e.getMessage().startsWith("Connection reset by peer"))
                LOG.trace(e.getMessage(), e);
            else if (e.getMessage() != null && e.getMessage().startsWith("An existing connection " +
                    "was forcibly closed"))
                Jvm.debug().on(getClass(), e.getMessage());

            else if (!(e instanceof ClosedByInterruptException))
                Jvm.warn().on(getClass(), "", e);

            // The remote server has sent you a RST packet, which indicates an immediate dropping of the connection,
            // rather than the usual handshake. This bypasses the normal half-closed state transition.
            // I like this description: "Connection reset by peer" is the TCP/IP equivalent
            // of slamming the phone back on the hook.

            if (heartbeatListener != null)
                heartbeatListener.onMissedHeartbeat();

        } finally {
            closeSC();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeSC();
            // NOTE Do not release buffers here as they might be in use. Wait for loopFinished() to do it
            // Note: loopFinished() may actually be called before close() if a InvalidEventHandlerException is thrown
        }
    }

    @PackageLocal
    void closeSC() {
        if (scClosed.compareAndSet(false, true)) {
            Closeable.closeQuietly(tcpHandler);
            Closeable.closeQuietly(this.nc.networkStatsListener());
            Closeable.closeQuietly(sc);
            Closeable.closeQuietly(nc);
        }
    }

    @PackageLocal
    boolean tryWrite(ByteBuffer outBB) throws IOException {
        if (outBB.remaining() <= 0)
            return false;
        int start = outBB.position();
        long writeTime = System.nanoTime();
        assert !sc.isBlocking();
        int wrote = sc.write(outBB);
        long time1 = System.nanoTime() - writeTime;
        if (time1 > NBW_WARNING_NANOS)
            Jvm.warn().on(getClass(), "Non blocking write took " + time1 / 1000 + " us.");
        tcpHandler.onWriteTime(writeTime, outBB, start, outBB.position());

        bytesWriteCount += (outBB.position() - start);
        writeLog.log(outBB, start, outBB.position());

        if (wrote < 0) {
            closeSC();
        } else if (wrote > 0) {
            outBB.compact().flip();
            outBBB.writePosition(outBB.limit());
            return true;
        }
        return false;
    }

    public static class Factory<T extends NetworkContext<T>> implements MarshallableFunction<T, TcpEventHandler<T>> {
        public Factory() {
        }

        @NotNull
        @Override
        public TcpEventHandler<T> apply(@NotNull T nc) {
            return new TcpEventHandler<T>(nc);
        }
    }

    public boolean writeAction() {
        Jvm.optionalSafepoint();

        boolean busy = false;
        try {
            // get more data to write if the buffer was empty
            // or we can write some of what is there
            ByteBuffer outBB = outBBB.underlyingObject();
            int remaining = outBB.remaining();
            busy = remaining > 0;
            if (busy)
                tryWrite(outBB);

            // has the remaining changed, i.e. did it write anything?
            if (outBB.remaining() == remaining) {
                busy |= invokeHandler();
                if (!busy)
                    busy = tryWrite(outBB);
            }
        } catch (ClosedChannelException cce) {
            closeSC();

        } catch (IOException e) {
            if (!closed.get())
                handleIOE(e, tcpHandler.hasClientClosed(), nc.heartbeatListener());
        }
        return busy;
    }
}
