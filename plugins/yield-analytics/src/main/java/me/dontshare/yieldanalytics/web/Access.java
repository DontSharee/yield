package me.dontshare.yieldanalytics.web;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who may use the site, and how much.
 * <ul>
 *   <li>A <b>whitelisted</b> address is an editor: no code needed, never
 *       signed out, and allowed to change players.</li>
 *   <li>Anyone else is a <b>viewer</b> with the current viewer code, which
 *       is replaced every few minutes - and every viewer still holding the
 *       old one is signed out when it is.</li>
 * </ul>
 * Thread-safe: read by the web threads, changed from commands.
 */
public final class Access {

    /** What a request may do. */
    public enum Role { NONE, VIEWER, EDITOR }

    /** Who a request is: the address it came from, what it may do, and a name for the edit log. */
    public record Caller(String ip, Role role, String name) {
    }

    /** No 0/O or 1/I/L - read out loud or typed from a screenshot. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final long codeLifetimeMs;
    private volatile List<Entry> whitelist = List.of();
    private volatile List<Entry> trustedProxies = List.of();
    private volatile String code;
    private volatile long codeExpiresAt;

    public Access(List<String> whitelist, List<String> trustedProxies, int codeMinutes) {
        this.codeLifetimeMs = codeMinutes * 60_000L;
        setWhitelist(whitelist);
        this.trustedProxies = parse(trustedProxies);
        rotate();
    }

    // ------------------------------------------------------------------ viewer code

    /** A new viewer code now - everyone on the old one is signed out. */
    public synchronized void rotate() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        code = new String(chars);
        codeExpiresAt = System.currentTimeMillis() + codeLifetimeMs;
    }

    /** The current code, rotating it first if its time is up. */
    public String code() {
        if (System.currentTimeMillis() >= codeExpiresAt) {
            synchronized (this) {
                if (System.currentTimeMillis() >= codeExpiresAt) {
                    rotate();
                }
            }
        }
        return code;
    }

    public long codeExpiresAt() {
        code();
        return codeExpiresAt;
    }

    // ------------------------------------------------------------------ whitelist

    public List<String> whitelist() {
        return whitelist.stream().map(Entry::raw).toList();
    }

    public void setWhitelist(List<String> entries) {
        whitelist = parse(entries);
    }

    /** Checks an entry parses, throwing {@link IllegalArgumentException} with why not. */
    public static String validate(String raw) {
        Entry entry = Entry.parse(raw);
        if (entry == null) {
            throw new IllegalArgumentException("Not an IP address or range: " + raw);
        }
        return entry.raw();
    }

    // ------------------------------------------------------------------ deciding

    /**
     * Who a request is. {@code remote} is the connection's own address;
     * {@code forwardedFor} the X-Forwarded-For header, trusted only when
     * {@code remote} is a listed proxy; {@code given} the code it sent.
     */
    public Caller identify(String remote, String forwardedFor, String given) {
        String ip = clientIp(remote, forwardedFor);
        for (Entry entry : whitelist) {
            if (entry.matches(ip)) {
                return new Caller(ip, Role.EDITOR, entry.name() != null ? entry.name() : ip);
            }
        }
        // Constant-time, so response timing can't be used to guess the code.
        if (given != null && MessageDigest.isEqual(code().getBytes(StandardCharsets.UTF_8),
                given.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            return new Caller(ip, Role.VIEWER, "viewer " + ip);
        }
        return new Caller(ip, Role.NONE, ip);
    }

    /** Who a request is on its code alone, ignoring the whitelist. */
    public Caller identifyByCode(String ip, String given) {
        if (given != null && MessageDigest.isEqual(code().getBytes(StandardCharsets.UTF_8),
                given.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            return new Caller(ip, Role.VIEWER, "viewer " + ip);
        }
        return new Caller(ip, Role.NONE, ip);
    }

    /** The real client behind any trusted proxies: the right-most X-Forwarded-For hop that isn't one of them. */
    public String clientIp(String remote, String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrustedProxy(remote)) {
            return remote;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                return hop;
            }
        }
        return remote;
    }

    private boolean isTrustedProxy(String ip) {
        for (Entry entry : trustedProxies) {
            if (entry.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    private static List<Entry> parse(List<String> raw) {
        List<Entry> entries = new ArrayList<>();
        for (String line : raw) {
            Entry entry = Entry.parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    /** One whitelist line: an address or CIDR range, and an optional name. */
    private record Entry(String raw, byte[] network, int prefix, String name) {

        static Entry parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            String address = line;
            String name = null;
            int equals = line.indexOf('=');
            if (equals >= 0) {
                address = line.substring(0, equals);
                name = line.substring(equals + 1).trim();
                if (name.isEmpty()) {
                    name = null;
                }
            }
            address = address.trim();
            int slash = address.indexOf('/');
            String host = slash >= 0 ? address.substring(0, slash) : address;
            if (!host.matches("[0-9a-fA-F:.]+")) {
                return null; // never a hostname lookup
            }
            byte[] bytes;
            try {
                bytes = InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(address.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (prefix < 0 || prefix > bytes.length * 8) {
                    return null;
                }
            }
            String canonical = host + (slash >= 0 ? "/" + prefix : "") + (name != null ? " = " + name : "");
            return new Entry(canonical, bytes, prefix, name);
        }

        boolean matches(String ip) {
            if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) {
                return false;
            }
            byte[] candidate;
            try {
                candidate = InetAddress.getByName(ip).getAddress();
            } catch (UnknownHostException e) {
                return false;
            }
            if (candidate.length != network.length) {
                return false;
            }
            BigInteger mask = prefix == 0 ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(prefix).subtract(BigInteger.ONE).shiftLeft(network.length * 8 - prefix);
            return new BigInteger(1, candidate).and(mask).equals(new BigInteger(1, network).and(mask));
        }
    }
}
