package com.lido.vpn

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray

object XrayConfigGenerator {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generateConfig(
        server: VpnServer, 
        dns: String, 
        sniffing: Boolean, 
        mux: Boolean, 
        fragment: Boolean,
        routingMode: String = "BYPASS_LAN_RU",
        mtu: Int = 1500,
        assetPath: String = "",
        utlsFingerprint: String = "chrome",
        dpiPackets: String = "tlshello",
        dpiLength: String = "100-200",
        dpiInterval: String = "10-20",
        isTestConfig: Boolean = false,
        socksProxyPort: Int = 0
    ): String {
        val config = JsonObject()
        
        val log = JsonObject()
        log.addProperty("loglevel", "error")
        config.add("log", log)

        val inbounds = JsonArray()
        if (!isTestConfig) {
            val tunInbound = JsonObject()
            tunInbound.addProperty("protocol", "tun")
            tunInbound.addProperty("tag", "tun-in")
            val tunSettings = JsonObject()
            tunSettings.addProperty("mtu", mtu)
            tunInbound.add("settings", tunSettings)
            
            val sniffingObj = JsonObject()
            sniffingObj.addProperty("enabled", sniffing)
            val destOverride = JsonArray()
            destOverride.add("http")
            destOverride.add("tls")
            sniffingObj.add("destOverride", destOverride)
            tunInbound.add("sniffing", sniffingObj)
            inbounds.add(tunInbound)
            
            val socksInbound = JsonObject()
            socksInbound.addProperty("port", 10808)
            socksInbound.addProperty("protocol", "socks")
            val socksSettings = JsonObject()
            socksSettings.addProperty("udp", true)
            socksInbound.add("settings", socksSettings)
            inbounds.add(socksInbound)
        } else {
            val testInbound = JsonObject()
            testInbound.addProperty("protocol", "socks")
            testInbound.addProperty("port", 1080)
            inbounds.add(testInbound)
        }
        config.add("inbounds", inbounds)

        val outbounds = JsonArray()
        val isDpiOnly = server.type == "DPI_ONLY"
        
        if (socksProxyPort > 0) {
            val socksOutbound = JsonObject()
            socksOutbound.addProperty("protocol", "socks")
            socksOutbound.addProperty("tag", "proxy")
            val settings = JsonObject()
            val servers = JsonArray()
            val node = JsonObject()
            node.addProperty("address", "127.0.0.1")
            node.addProperty("port", socksProxyPort)
            servers.add(node)
            settings.add("servers", servers)
            socksOutbound.add("settings", settings)
            outbounds.add(socksOutbound)
        } else {
            if (isDpiOnly || fragment) {
                val baseDialer = if (isDpiOnly) "direct" else "proxy"
                val lengths = dpiLength.split(",")
                
                if (lengths.size > 1) {
                    // Создаем цепочку из нескольких outbounds для каждой позиции разреза
                    lengths.forEachIndexed { index, len ->
                        val tag = "fragment-out-$index"
                        val nextTag = if (index == lengths.size - 1) baseDialer else "fragment-out-${index + 1}"
                        
                        val out = JsonObject()
                        out.addProperty("protocol", "freedom")
                        out.addProperty("tag", tag)
                        
                        val settings = JsonObject()
                        val frag = JsonObject()
                        if (dpiPackets != "all") {
                            frag.addProperty("packets", dpiPackets)
                        }
                        frag.addProperty("length", len)
                        frag.addProperty("interval", dpiInterval)
                        settings.add("fragment", frag)
                        out.add("settings", settings)
                        
                        val stream = JsonObject()
                        val sockopt = JsonObject()
                        sockopt.addProperty("dialerProxy", nextTag)
                        stream.add("sockopt", sockopt)
                        out.add("streamSettings", stream)
                        
                        outbounds.add(out)
                    }
                } else {
                    // Обычный одиночный фрагмент
                    val out = JsonObject()
                    out.addProperty("protocol", "freedom")
                    out.addProperty("tag", "fragment-out")
                    
                    val settings = JsonObject()
                    val frag = JsonObject()
                    if (dpiPackets != "all") {
                        frag.addProperty("packets", dpiPackets)
                    }
                    frag.addProperty("length", dpiLength)
                    frag.addProperty("interval", dpiInterval)
                    settings.add("fragment", frag)
                    out.add("settings", settings)
                    
                    val stream = JsonObject()
                    val sockopt = JsonObject()
                    sockopt.addProperty("dialerProxy", baseDialer)
                    stream.add("sockopt", sockopt)
                    out.add("streamSettings", stream)
                    
                    outbounds.add(out)
                }
            }

            if (!isDpiOnly) {
                val proxyOutbound = JsonObject()
                var protocol = server.type.lowercase()
                if (protocol == "ss") protocol = "shadowsocks"
                proxyOutbound.addProperty("protocol", protocol)
                proxyOutbound.addProperty("tag", "proxy")
                
                val settings = JsonObject()
                when (protocol) {
                    "vless" -> {
                        val vnext = JsonArray()
                        val node = JsonObject()
                        node.addProperty("address", server.host)
                        node.addProperty("port", server.port)
                        val users = JsonArray()
                        val user = JsonObject()
                        user.addProperty("id", server.uuid)
                        user.addProperty("encryption", "none")
                        users.add(user)
                        node.add("users", users)
                        vnext.add(node)
                        settings.add("vnext", vnext)
                    }
                    "vmess" -> {
                        val vnext = JsonArray()
                        val node = JsonObject()
                        node.addProperty("address", server.host)
                        node.addProperty("port", server.port)
                        val users = JsonArray()
                        val user = JsonObject()
                        user.addProperty("id", server.uuid)
                        users.add(user)
                        node.add("users", users)
                        vnext.add(node)
                        settings.add("vnext", vnext)
                    }
                    "trojan" -> {
                        val servers = JsonArray()
                        val node = JsonObject()
                        node.addProperty("address", server.host)
                        node.addProperty("port", server.port)
                        node.addProperty("password", server.uuid)
                        servers.add(node)
                        settings.add("servers", servers)
                    }
                    "shadowsocks" -> {
                        val servers = JsonArray()
                        val node = JsonObject()
                        node.addProperty("address", server.host)
                        node.addProperty("port", server.port)
                        val userInfo = server.uuid
                        if (userInfo.contains(":")) {
                            val parts = userInfo.split(":")
                            node.addProperty("method", parts[0])
                            node.addProperty("password", parts[1])
                        } else {
                            node.addProperty("method", "aes-256-gcm")
                            node.addProperty("password", userInfo)
                        }
                        servers.add(node)
                        settings.add("servers", servers)
                    }
                }
                proxyOutbound.add("settings", settings)

                val streamSettings = JsonObject()
                val network = server.params["net"] ?: server.params["type"] ?: "tcp"
                streamSettings.addProperty("network", network)
                
                val security = server.params["security"] ?: "none"
                streamSettings.addProperty("security", security)
                
                if (security == "tls" || security == "reality") {
                    val tlsKey = if (security == "reality") "realitySettings" else "tlsSettings"
                    val tlsObj = JsonObject()
                    tlsObj.addProperty("serverName", server.params["sni"] ?: server.host)
                    tlsObj.addProperty("fingerprint", server.params["fp"] ?: utlsFingerprint)
                    if (security == "reality") {
                        tlsObj.addProperty("publicKey", server.params["pbk"] ?: "")
                        tlsObj.addProperty("shortId", server.params["sid"] ?: "")
                    }
                    streamSettings.add(tlsKey, tlsObj)
                }
                proxyOutbound.add("streamSettings", streamSettings)
                outbounds.add(proxyOutbound)
            }
        }

        val directOutbound = JsonObject()
        directOutbound.addProperty("protocol", "freedom")
        directOutbound.addProperty("tag", "direct")
        outbounds.add(directOutbound)

        val dnsOutbound = JsonObject()
        dnsOutbound.addProperty("protocol", "dns")
        dnsOutbound.addProperty("tag", "dns-out")
        outbounds.add(dnsOutbound)

        config.add("outbounds", outbounds)

        val routing = JsonObject()
        routing.addProperty("domainStrategy", "IPIfNonMatch")
        val rules = JsonArray()
        
        val dnsRule = JsonObject()
        dnsRule.addProperty("type", "field")
        dnsRule.addProperty("outboundTag", "dns-out")
        dnsRule.addProperty("port", 53)
        rules.add(dnsRule)
        
        val effectiveMainTag = if (socksProxyPort > 0) {
            "proxy"
        } else if (isDpiOnly || fragment) {
             if (dpiLength.contains(",")) "fragment-out-0" else "fragment-out"
        } else {
            "proxy"
        }

        if (routingMode == "BYPASS_LAN_RU") {
            val lanRule = JsonObject()
            lanRule.addProperty("type", "field")
            lanRule.addProperty("outboundTag", "direct")
            val lanIp = JsonArray().apply { add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16") }
            lanRule.add("ip", lanIp)
            rules.add(lanRule)
        }

        val mainRule = JsonObject()
        mainRule.addProperty("type", "field")
        mainRule.addProperty("outboundTag", effectiveMainTag)
        mainRule.addProperty("network", "udp,tcp")
        rules.add(mainRule)

        routing.add("rules", rules)
        config.add("routing", routing)

        val dnsObj = JsonObject()
        val dnsServers = JsonArray()
        val dnsSrv = JsonObject()
        dnsSrv.addProperty("address", dns)
        dnsSrv.addProperty("port", 53)
        dnsServers.add(dnsSrv)
        dnsObj.add("servers", dnsServers)
        config.add("dns", dnsObj)

        return gson.toJson(config)
    }
}
