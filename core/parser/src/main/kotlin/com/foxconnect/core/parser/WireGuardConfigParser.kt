package com.foxconnect.core.parser

import com.foxconnect.core.model.WireGuardPeer
import com.foxconnect.core.model.WireGuardProfile
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Parses the standard WireGuard INI format without accepting shell-style extensions. */
object WireGuardConfigParser {
    private const val MAX_LINES = 2_048
    private const val MAX_PEERS = 64

    fun parse(raw: String): ParseResult<WireGuardProfile> {
        val text = raw.trim().removePrefix("\uFEFF")
        if (!text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
                .equals("[Interface]", ignoreCase = true)
        ) {
            return ParseResult.Error("wrong_format", "این متن کانفیگ استاندارد WireGuard نیست")
        }
        val interfaceValues = linkedMapOf<String, MutableList<String>>()
        val peerValues = mutableListOf<MutableMap<String, MutableList<String>>>()
        var current: MutableMap<String, MutableList<String>>? = null
        val lines = text.lineSequence().toList()
        if (lines.size > MAX_LINES) return ParseResult.Error("too_many_lines", "کانفیگ WireGuard بیش از حد طولانی است")

        for (sourceLine in lines) {
            val line = sourceLine.trim()
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) continue
            when {
                line.equals("[Interface]", ignoreCase = true) -> {
                    if (current != null) return invalidStructure()
                    current = interfaceValues
                }
                line.equals("[Peer]", ignoreCase = true) -> {
                    if (interfaceValues.isEmpty() || peerValues.size >= MAX_PEERS) return invalidStructure()
                    current = linkedMapOf<String, MutableList<String>>().also(peerValues::add)
                }
                line.startsWith('[') -> return ParseResult.Error(
                    "unsupported_section",
                    "بخش پشتیبانی‌نشده در کانفیگ WireGuard وجود دارد",
                )
                else -> {
                    val target = current ?: return invalidStructure()
                    val pieces = line.split('=', limit = 2)
                    if (pieces.size != 2) return invalidStructure()
                    val key = pieces[0].trim().lowercase()
                    val value = pieces[1].trim()
                    val allowed = if (target === interfaceValues) INTERFACE_KEYS else PEER_KEYS
                    if (key !in allowed) {
                        return ParseResult.Error("unsupported_option", "گزینهٔ پشتیبانی‌نشده در WireGuard وجود دارد", key.take(64))
                    }
                    if (value.isBlank()) return invalidStructure()
                    target.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
        }
        if (peerValues.isEmpty()) return ParseResult.Error("missing_peer", "هیچ Peer در کانفیگ WireGuard وجود ندارد")
        if (INTERFACE_SINGLE_KEYS.any { interfaceValues[it]?.size?.let { size -> size != 1 } == true } ||
            peerValues.any { peer -> PEER_SINGLE_KEYS.any { peer[it]?.size?.let { size -> size != 1 } == true } }
        ) {
            return ParseResult.Error("duplicate_option", "گزینهٔ تکراری و مبهم در WireGuard وجود دارد")
        }

        val privateKey = interfaceValues.single("privatekey")
            ?.takeIf(::isWireGuardKey)
            ?: return ParseResult.Error("invalid_private_key", "کلید خصوصی WireGuard معتبر نیست")
        val localAddresses = interfaceValues.csv("address")
        if (localAddresses.isEmpty() || localAddresses.any { !isIpPrefix(it) }) {
            return ParseResult.Error("invalid_address", "Address در کانفیگ WireGuard معتبر نیست")
        }
        val dnsServers = interfaceValues.csv("dns")
        if (dnsServers.any { !isNumericIp(it) } || dnsServers.size > 8) {
            return ParseResult.Error("invalid_dns", "DNS در کانفیگ WireGuard باید IP معتبر باشد")
        }
        val mtuValue = interfaceValues.single("mtu")?.toIntOrNull()
        if (interfaceValues.containsKey("mtu") && mtuValue == null) {
            return ParseResult.Error("invalid_mtu", "MTU در WireGuard معتبر نیست")
        }
        val mtu = mtuValue ?: 1408
        if (mtu !in 576..9_000) return ParseResult.Error("invalid_mtu", "MTU در WireGuard معتبر نیست")
        val listenPort = interfaceValues.single("listenport")?.toIntOrNull()
        if (interfaceValues.containsKey("listenport") && listenPort !in 1..65535) {
            return ParseResult.Error("invalid_listen_port", "ListenPort در WireGuard معتبر نیست")
        }

        val peers = mutableListOf<WireGuardPeer>()
        peerValues.forEach { values ->
            val publicKey = values.single("publickey")?.takeIf(::isWireGuardKey)
                ?: return ParseResult.Error("invalid_public_key", "کلید عمومی Peer در WireGuard معتبر نیست")
            val preSharedKey = values.single("presharedkey")
            if (preSharedKey != null && !isWireGuardKey(preSharedKey)) {
                return ParseResult.Error("invalid_preshared_key", "کلید اشتراکی Peer در WireGuard معتبر نیست")
            }
            val endpoint = values.single("endpoint")?.let(::parseEndpoint)
                ?: return ParseResult.Error("invalid_endpoint", "Endpoint در کانفیگ WireGuard معتبر نیست")
            val allowedIps = values.csv("allowedips")
            if (allowedIps.isEmpty() || allowedIps.any { !isIpPrefix(it) }) {
                return ParseResult.Error("invalid_allowed_ips", "AllowedIPs در WireGuard معتبر نیست")
            }
            val keepaliveRaw = values.single("persistentkeepalive")?.toIntOrNull()
            if (values.containsKey("persistentkeepalive") && keepaliveRaw !in 0..65_535) {
                return ParseResult.Error("invalid_keepalive", "PersistentKeepalive در WireGuard معتبر نیست")
            }
            val reserved = values.csv("reserved").mapNotNull(String::toIntOrNull)
            if (values.containsKey("reserved") && (reserved.size != 3 || reserved.any { it !in 0..255 })) {
                return ParseResult.Error("invalid_reserved", "Reserved در WireGuard باید سه byte معتبر باشد")
            }
            peers += WireGuardPeer(
                address = endpoint.first,
                port = endpoint.second,
                publicKey = publicKey,
                preSharedKey = preSharedKey,
                allowedIps = allowedIps,
                persistentKeepaliveSeconds = keepaliveRaw?.takeIf { it > 0 },
                reserved = reserved,
            )
        }

        val first = peers.first()
        val id = stableId(
            privateKey,
            localAddresses.sorted().joinToString(","),
            peers.joinToString("|") { peer ->
                listOf(
                    peer.address.lowercase(), peer.port.toString(), peer.publicKey,
                    peer.preSharedKey.orEmpty(), peer.allowedIps.sorted().joinToString(","),
                    peer.persistentKeepaliveSeconds?.toString().orEmpty(), peer.reserved.joinToString(","),
                ).joinToString(";")
            },
            mtu.toString(),
        )
        return ParseResult.Success(
            WireGuardProfile(
                id = id,
                name = "${first.address}:${first.port}".take(120),
                host = first.address,
                port = first.port,
                privateKey = privateKey,
                localAddresses = localAddresses,
                peers = peers,
                dnsServers = dnsServers,
                mtu = mtu,
                listenPort = listenPort,
            ),
        )
    }

    private fun MutableMap<String, MutableList<String>>.single(key: String): String? {
        val values = this[key] ?: return null
        if (values.size != 1) return null
        return values.single()
    }

    private fun MutableMap<String, MutableList<String>>.csv(key: String): List<String> =
        this[key].orEmpty().flatMap { value -> value.split(',') }.map(String::trim).filter(String::isNotBlank)

    private fun parseEndpoint(value: String): Pair<String, Int>? {
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close <= 1 || close + 2 >= value.length || value[close + 1] != ':') return null
            host = value.substring(1, close)
            portText = value.substring(close + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535 || host.length > 253 || host.any(Char::isWhitespace)) return null
        if (!isNumericIp(host) && !host.matches(Regex("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))) return null
        return host to port
    }

    private fun isWireGuardKey(value: String): Boolean = runCatching {
        Base64.getDecoder().decode(value).size == 32
    }.getOrDefault(false)

    private fun isIpPrefix(value: String): Boolean {
        val pieces = value.split('/', limit = 2)
        if (pieces.size != 2 || !isNumericIp(pieces[0])) return false
        val bits = if (pieces[0].contains(':')) 128 else 32
        return pieces[1].toIntOrNull() in 0..bits
    }

    private fun isNumericIp(value: String): Boolean {
        if (!value.matches(Regex("[0-9A-Fa-f:.]+"))) return false
        return runCatching { InetAddress.getByName(value) }.isSuccess
    }

    private fun stableId(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun invalidStructure() = ParseResult.Error(
        "invalid_wireguard_structure",
        "ساختار کانفیگ WireGuard معتبر نیست",
    )

    private val INTERFACE_KEYS = setOf("privatekey", "address", "dns", "mtu", "listenport")
    private val INTERFACE_SINGLE_KEYS = setOf("privatekey", "mtu", "listenport")
    private val PEER_KEYS = setOf(
        "publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive", "reserved",
    )
    private val PEER_SINGLE_KEYS = PEER_KEYS - "allowedips"
}
