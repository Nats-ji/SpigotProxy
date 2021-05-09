package nl.thijsalders.spigotproxy.haproxy;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import nl.thijsalders.spigotproxy.haproxy.HAProxyProxiedProtocol.AddressFamily;

/**
 * Message container for decoded HAProxy proxy protocol parameters
 */
public final class HAProxyMessage {

    /**
     * Version 1 proxy protocol message for 'UNKNOWN' proxied protocols. Per spec, when the proxied protocol is
     * 'UNKNOWN' we must discard all other header values.
     */
    private static final HAProxyMessage V1_UNKNOWN_MSG = new HAProxyMessage(
            HAProxyProtocolVersion.V1, HAProxyCommand.PROXY, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0);

    /**
     * Version 2 proxy protocol message for 'UNKNOWN' proxied protocols. Per spec, when the proxied protocol is
     * 'UNKNOWN' we must discard all other header values.
     */
    private static final HAProxyMessage V2_UNKNOWN_MSG = new HAProxyMessage(
            HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0);

    /**
     * Version 2 proxy protocol message for local requests. Per spec, we should use an unspecified protocol and family
     * for 'LOCAL' commands. Per spec, when the proxied protocol is 'UNKNOWN' we must discard all other header values.
     */
    private static final HAProxyMessage V2_LOCAL_MSG = new HAProxyMessage(
            HAProxyProtocolVersion.V2, HAProxyCommand.LOCAL, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0);

    private final HAProxyProtocolVersion protocolVersion;
    private final HAProxyCommand command;
    private final HAProxyProxiedProtocol proxiedProtocol;
    private final String sourceAddress;
    private final String destinationAddress;
    private final int sourcePort;
    private final int destinationPort;

    /**
     * Creates a new instance
     */
    private HAProxyMessage(
            HAProxyProtocolVersion protocolVersion, HAProxyCommand command, HAProxyProxiedProtocol proxiedProtocol,
            String sourceAddress, String destinationAddress, String sourcePort, String destinationPort) {
        this(
                protocolVersion, command, proxiedProtocol,
                sourceAddress, destinationAddress, portStringToInt(sourcePort), portStringToInt(destinationPort));
    }

    /**
     * Creates a new instance
     */
    private HAProxyMessage(
            HAProxyProtocolVersion protocolVersion, HAProxyCommand command, HAProxyProxiedProtocol proxiedProtocol,
            String sourceAddress, String destinationAddress, int sourcePort, int destinationPort) {

        if (proxiedProtocol == null) {
            throw new NullPointerException("proxiedProtocol");
        }
        AddressFamily addressFamily = proxiedProtocol.addressFamily();

        checkAddress(sourceAddress, addressFamily);
        checkAddress(destinationAddress, addressFamily);
        checkPort(sourcePort);
        checkPort(destinationPort);

        this.protocolVersion = protocolVersion;
        this.command = command;
        this.proxiedProtocol = proxiedProtocol;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
    }

    /**
     * Decodes a version 2, binary proxy protocol header.
     *
     * @param header                     a version 2 proxy protocol header
     * @return                           {@link HAProxyMessage} instance
     * @throws HAProxyProtocolException  if any portion of the header is invalid
     */
    static HAProxyMessage decodeHeader(ByteBuf header) {
        if (header == null) {
            throw new NullPointerException("header");
        }

        if (header.readableBytes() < 16) {
            throw new HAProxyProtocolException(
                    "incomplete header: " + header.readableBytes() + " bytes (expected: 16+ bytes)");
        }

        // Per spec, the 13th byte is the protocol version and command byte
        header.skipBytes(12);
        final byte verCmdByte = header.readByte();

        HAProxyProtocolVersion ver;
        try {
            ver = HAProxyProtocolVersion.valueOf(verCmdByte);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (ver != HAProxyProtocolVersion.V2) {
            throw new HAProxyProtocolException("version 1 unsupported: 0x" + Integer.toHexString(verCmdByte));
        }

        HAProxyCommand cmd;
        try {
            cmd = HAProxyCommand.valueOf(verCmdByte);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (cmd == HAProxyCommand.LOCAL) {
            return V2_LOCAL_MSG;
        }

        // Per spec, the 14th byte is the protocol and address family byte
        HAProxyProxiedProtocol protocolAndFamily;
        try {
            protocolAndFamily = HAProxyProxiedProtocol.valueOf(header.readByte());
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (protocolAndFamily == HAProxyProxiedProtocol.UNKNOWN) {
            return V2_UNKNOWN_MSG;
        }

        int addressInfoLen = header.readUnsignedShort();

        String srcAddress;
        String dstAddress;
        int addressLen;
        int srcPort = 0;
        int dstPort = 0;

        AddressFamily addressFamily = protocolAndFamily.addressFamily();

        if (addressFamily == AddressFamily.AF_UNIX) {
            // unix sockets require 216 bytes for address information
            if (addressInfoLen < 216 || header.readableBytes() < 216) {
                throw new HAProxyProtocolException(
                    "incomplete UNIX socket address information: " +
                            Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 216+ bytes)");
            }
            int startIdx = header.readerIndex();
            int addressEnd = header.forEachByte(startIdx, 108, ByteProcessor.FIND_NUL);
            if (addressEnd == -1) {
                addressLen = 108;
            } else {
                addressLen = addressEnd - startIdx;
            }
            srcAddress = header.toString(startIdx, addressLen, CharsetUtil.US_ASCII);

            startIdx += 108;

            addressEnd = header.forEachByte(startIdx, 108, ByteProcessor.FIND_NUL);
            if (addressEnd == -1) {
                addressLen = 108;
            } else {
                addressLen = addressEnd - startIdx;
            }
            dstAddress = header.toString(startIdx, addressLen, CharsetUtil.US_ASCII);
        } else {
            if (addressFamily == AddressFamily.AF_IPv4) {
                // IPv4 requires 12 bytes for address information
                if (addressInfoLen < 12 || header.readableBytes() < 12) {
                    throw new HAProxyProtocolException(
                        "incomplete IPv4 address information: " +
                                Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 12+ bytes)");
                }
                addressLen = 4;
            } else if (addressFamily == AddressFamily.AF_IPv6) {
                // IPv6 requires 36 bytes for address information
                if (addressInfoLen < 36 || header.readableBytes() < 36) {
                    throw new HAProxyProtocolException(
                        "incomplete IPv6 address information: " +
                                Math.min(addressInfoLen, header.readableBytes()) + " bytes (expected: 36+ bytes)");
                }
                addressLen = 16;
            } else {
                throw new HAProxyProtocolException(
                    "unable to parse address information (unknown address family: " + addressFamily + ')');
            }

            // Per spec, the src address begins at the 17th byte
            srcAddress = ipBytesToString(header, addressLen);
            dstAddress = ipBytesToString(header, addressLen);
            srcPort = header.readUnsignedShort();
            dstPort = header.readUnsignedShort();
        }

        return new HAProxyMessage(ver, cmd, protocolAndFamily, srcAddress, dstAddress, srcPort, dstPort);
    }

    /**
     * Decodes a version 1, human-readable proxy protocol header.
     *
     * @param header                     a version 1 proxy protocol header
     * @return                           {@link HAProxyMessage} instance
     * @throws HAProxyProtocolException  if any portion of the header is invalid
     */
    static HAProxyMessage decodeHeader(String header) {
        if (header == null) {
            throw new HAProxyProtocolException("header");
        }

        String[] parts = header.split(" ");
        int numParts = parts.length;

        if (numParts < 2) {
            throw new HAProxyProtocolException(
                    "invalid header: " + header + " (expected: 'PROXY' and proxied protocol values)");
        }

        if (!"PROXY".equals(parts[0])) {
            throw new HAProxyProtocolException("unknown identifier: " + parts[0]);
        }

        HAProxyProxiedProtocol protocolAndFamily;
        try {
            protocolAndFamily = HAProxyProxiedProtocol.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new HAProxyProtocolException(e);
        }

        if (protocolAndFamily != HAProxyProxiedProtocol.TCP4 &&
                protocolAndFamily != HAProxyProxiedProtocol.TCP6 &&
                protocolAndFamily != HAProxyProxiedProtocol.UNKNOWN) {
            throw new HAProxyProtocolException("unsupported v1 proxied protocol: " + parts[1]);
        }

        if (protocolAndFamily == HAProxyProxiedProtocol.UNKNOWN) {
            return V1_UNKNOWN_MSG;
        }

        if (numParts != 6) {
            throw new HAProxyProtocolException("invalid TCP4/6 header: " + header + " (expected: 6 parts)");
        }

        return new HAProxyMessage(
                HAProxyProtocolVersion.V1, HAProxyCommand.PROXY,
                protocolAndFamily, parts[2], parts[3], parts[4], parts[5]);
    }

    /**
     * Convert ip address bytes to string representation
     *
     * @param header     buffer containing ip address bytes
     * @param addressLen number of bytes to read (4 bytes for IPv4, 16 bytes for IPv6)
     * @return           string representation of the ip address
     */
    private static String ipBytesToString(ByteBuf header, int addressLen) {
        StringBuilder sb = new StringBuilder();
        if (addressLen == 4) {
            sb.append(header.readByte() & 0xff);
            sb.append('.');
            sb.append(header.readByte() & 0xff);
            sb.append('.');
            sb.append(header.readByte() & 0xff);
            sb.append('.');
            sb.append(header.readByte() & 0xff);
        } else {
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
            sb.append(':');
            sb.append(Integer.toHexString(header.readUnsignedShort()));
        }
        return sb.toString();
    }

    /**
     * Convert port to integer
     *
     * @param value                      the port
     * @return                           port as an integer
     * @throws HAProxyProtocolException  if port is not a valid integer
     */
    private static int portStringToInt(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new HAProxyProtocolException("invalid port: " + value, e);
        }

        if (port <= 0 || port > 65535) {
            throw new HAProxyProtocolException("invalid port: " + value + " (expected: 1 ~ 65535)");
        }

        return port;
    }

    /**
     * Validate an address (IPv4, IPv6, Unix Socket)
     *
     * @param address                   human-readable address
     * @param addressFamily             the {@link AddressFamily} to check the address against
     * @throws HAProxyProtocolException if the address is invalid
     */
    @SuppressWarnings("incomplete-switch")
	private static void checkAddress(String address, AddressFamily addressFamily) {
        if (addressFamily == null) {
            throw new NullPointerException("addressFamily");
        }

        switch (addressFamily) {
            case AF_UNSPEC:
                if (address != null)
                    throw new HAProxyProtocolException("Unable to validate an AF_UNSPEC address: " + address);
                return;
            case AF_UNIX:
                return;
        }

        if (address == null) {
            throw new NullPointerException("address");
        }

        switch (addressFamily) {
            case AF_IPv4:
                if (!NetUtil.isValidIpV4Address(address)) {
                    throw new HAProxyProtocolException("Invalid IPv4 address: " + address);
                }
                break;
            case AF_IPv6:
                if (!NetUtil.isValidIpV6Address(address)) {
                    throw new HAProxyProtocolException("Invalid IPv6 address: " + address);
                }
                break;
            default:
                throw new Error();
        }
    }

    /**
     * Validate a UDP/TCP port
     *
     * @param port                      the UDP/TCP port
     * @throws HAProxyProtocolException if the port is out of range (0-65535 inclusive)
     */
    private static void checkPort(int port) {
        if (port < 0 || port > 65535) {
            throw new HAProxyProtocolException("Invalid port: " + port + " (expected: 1 ~ 65535)");
        }
    }

    /**
     * Returns the {@link HAProxyProtocolVersion} of this {@link HAProxyMessage}.
     * @return {@link HAProxyProtocolVersion} of this {@link HAProxyMessage}.
     */
    public HAProxyProtocolVersion protocolVersion() {
        return protocolVersion;
    }

    /**
     * Returns the {@link HAProxyCommand} of this {@link HAProxyMessage}.
     * @return {@link HAProxyCommand} of this {@link HAProxyMessage}.
     */
    public HAProxyCommand command() {
        return command;
    }

    /**
     * Returns the {@link HAProxyProxiedProtocol} of this {@link HAProxyMessage}.
     * @return {@link HAProxyProxiedProtocol} of this {@link HAProxyMessage}.
     */
    public HAProxyProxiedProtocol proxiedProtocol() {
        return proxiedProtocol;
    }

    /**
     * Returns the human-readable source address of this {@link HAProxyMessage}.
     * @return the human-readable source address of this {@link HAProxyMessage}.
     */
    public String sourceAddress() {
        return sourceAddress;
    }

    /**
     * Returns the human-readable destination address of this {@link HAProxyMessage}.
     * @return the human-readable destination address of this {@link HAProxyMessage}.
     */
    public String destinationAddress() {
        return destinationAddress;
    }

    /**
     * Returns the UDP/TCP source port of this {@link HAProxyMessage}.
     * @return UDP/TCP source port of this {@link HAProxyMessage}.
     */
    public int sourcePort() {
        return sourcePort;
    }

    /**
     * Returns the UDP/TCP destination port of this {@link HAProxyMessage}.
     * @return UDP/TCP destination port of this {@link HAProxyMessage}.
     */
    public int destinationPort() {
        return destinationPort;
    }
}