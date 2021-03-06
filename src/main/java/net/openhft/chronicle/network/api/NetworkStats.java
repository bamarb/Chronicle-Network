/*
 * Copyright (c) 2016-2019 Chronicle Software Ltd
 */

package net.openhft.chronicle.network.api;

import net.openhft.chronicle.wire.SelfDescribingMarshallable;
import net.openhft.chronicle.wire.WireType;

import java.util.UUID;

public class NetworkStats extends SelfDescribingMarshallable {

    private long writeBps;
    private long readBps;
    private long timestamp;
    private long index;

    private long writeEwma;
    private long readEwma;
    private long socketPollRate;

    private int localIdentifier;
    private int p50, p90, p99, p99_9;
    private int remoteIdentifier;

    private boolean connected;
    private boolean acceptor;

    private WireType wireType;
    private UUID clientId;
    private String remoteHostName;
    private int remotePort;
    private String userId;
    private String host;

    public String userId() {
        return userId;
    }

    public NetworkStats userId(String userId) {
        this.userId = userId;
        return this;
    }

    public long writeBps() {
        return writeBps;
    }

    public NetworkStats writeBps(long writeBps) {
        this.writeBps = writeBps;
        return this;
    }

    public long readBps() {
        return readBps;
    }

    public NetworkStats readBps(long readBps) {
        this.readBps = readBps;
        return this;
    }

    public long writeEwma() {
        return writeEwma;
    }

    public NetworkStats writeEwma(long writeEwma) {
        this.writeEwma = writeEwma;
        return this;
    }

    public long readEwma() {
        return readEwma;
    }

    public NetworkStats readEwma(long readEwma) {
        this.readEwma = readEwma;
        return this;
    }

    public long index() {
        return index;
    }

    public NetworkStats index(long index) {
        this.index = index;
        return this;
    }

    public long timestamp() {
        return timestamp;
    }

    public NetworkStats timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public long socketPollRate() {
        return socketPollRate;
    }

    public NetworkStats socketPollRate(long socketPollRate) {
        this.socketPollRate = socketPollRate;
        return this;
    }

    public int localIdentifier() {
        return localIdentifier;
    }

    public NetworkStats localIdentifier(int localIdentifier) {
        this.localIdentifier = localIdentifier;
        return this;
    }

    public int p50() {
        return p50;
    }

    public NetworkStats p50(int p50) {
        this.p50 = p50;
        return this;
    }

    public int p90() {
        return p90;
    }

    public NetworkStats p90(int p90) {
        this.p90 = p90;
        return this;
    }

    public int p99() {
        return p99;
    }

    public NetworkStats p99(int p99) {
        this.p99 = p99;
        return this;
    }

    public int p99_9() {
        return p99_9;
    }

    public NetworkStats p99_9(int p99_9) {
        this.p99_9 = p99_9;
        return this;
    }

    public int remoteIdentifier() {
        return remoteIdentifier;
    }

    public NetworkStats remoteIdentifier(int remoteIdentifier) {
        this.remoteIdentifier = remoteIdentifier;
        return this;
    }

    public boolean connected() {
        return connected;
    }

    public NetworkStats connected(boolean connected) {
        this.connected = connected;
        return this;
    }

    public boolean acceptor() {
        return acceptor;
    }

    public NetworkStats acceptor(boolean acceptor) {
        this.acceptor = acceptor;
        return this;
    }

    public WireType wireType() {
        return wireType;
    }

    public NetworkStats wireType(WireType wireType) {
        this.wireType = wireType;
        return this;
    }

    public UUID clientId() {
        return clientId;
    }

    public NetworkStats clientId(UUID clientId) {
        this.clientId = clientId;
        return this;
    }

    public String remoteHostName() {
        return remoteHostName;
    }

    public NetworkStats remoteHostName(String remoteHostName) {
        this.remoteHostName = remoteHostName;
        return this;
    }

    public int remotePort() {
        return remotePort;
    }

    public NetworkStats remotePort(int remotePort) {
        this.remotePort = remotePort;
        return this;
    }

    public String host() {
        return host;
    }

    public NetworkStats host(String host) {
        this.host = host;
        return this;
    }
}