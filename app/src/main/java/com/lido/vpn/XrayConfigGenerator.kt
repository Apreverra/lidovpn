package com.lido.vpn

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray

object XrayConfigGenerator {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Suppress("UNUSED_PARAMETER")
    fun generateConfig(
        server: VpnServer, 
        dns: String, 
        sniffing: Boolean, 
        mux: Boolean, 
        fragment: Boolean,
        routingMode: String = "BYPASS_LAN_RU",
        mtu: Int = 1500,
        assetPath: String = "",
        utlsFingerprint: String = "chrome"
    ): String {
        val config = JsonObject()
        
        // Log
        val log = JsonObject()
        log.addProperty("loglevel", "warning")
        config.add("log", log)

        // Inbounds
        val inbounds = JsonArray()
        
        // TUN Inbound
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
        
        // SOCKS Inbound
        val socksInbound = JsonObject()
        socksInbound.addProperty("port", 10808)
        socksInbound.addProperty("protocol", "socks")
        val socksSettings = JsonObject()
        socksSettings.addProperty("udp", true)
        socksInbound.add("settings", socksSettings)
        socksInbound.add("sniffing", sniffingObj)
        inbounds.add(socksInbound)

        // HTTP Inbound
        val httpInbound = JsonObject()
        httpInbound.addProperty("port", 10809)
        httpInbound.addProperty("protocol", "http")
        inbounds.add(httpInbound)

        config.add("inbounds", inbounds)

        // Outbounds
        val outbounds = JsonArray()
        
        // Main Proxy Outbound
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
                user.addProperty("level", 0)
                
                val flow = server.params["flow"]
                if (!flow.isNullOrEmpty()) {
                    user.addProperty("flow", flow)
                }
                
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
                user.addProperty("alterId", 0)
                user.addProperty("security", "auto")
                user.addProperty("level", 0)
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
                node.addProperty("level", 0)
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
                    // Simple attempt to handle base64 userInfo if needed
                    // In a real app we'd use a proper Base64 decoder
                    node.addProperty("method", "aes-256-gcm") // Fallback
                    node.addProperty("password", userInfo)
                }
                node.addProperty("level", 0)
                servers.add(node)
                settings.add("servers", servers)
            }
        }
        proxyOutbound.add("settings", settings)

        // Stream Settings
        val streamSettings = JsonObject()
        val network = server.params["net"] ?: server.params["type"] ?: "tcp"
        val validNetworks = listOf("tcp", "kcp", "ws", "http", "domainsocket", "quic", "grpc")
        streamSettings.addProperty("network", if (network in validNetworks) network else "tcp")
        
        val security = server.params["security"] ?: "none"
        streamSettings.addProperty("security", security)
        
        if ((security == "tls") || (security == "xtls")) {
            val tlsSettings = JsonObject()
            tlsSettings.addProperty("serverName", server.params["sni"] ?: server.host)
            tlsSettings.addProperty("allowInsecure", false)
            tlsSettings.addProperty("fingerprint", server.params["fp"] ?: utlsFingerprint)
            streamSettings.add("tlsSettings", tlsSettings)
        } else if (security == "reality") {
            val realitySettings = JsonObject()
            realitySettings.addProperty("show", false)
            realitySettings.addProperty("fingerprint", server.params["fp"] ?: utlsFingerprint)
            realitySettings.addProperty("serverName", server.params["sni"] ?: server.host)
            realitySettings.addProperty("publicKey", server.params["pbk"] ?: "")
            realitySettings.addProperty("shortId", server.params["sid"] ?: "")
            realitySettings.addProperty("spiderX", server.params["spx"] ?: "")
            streamSettings.add("realitySettings", realitySettings)
        }
        
        if (network == "ws") {
            val wsSettings = JsonObject()
            wsSettings.addProperty("path", server.params["path"] ?: "/")
            val headers = JsonObject()
            headers.addProperty("Host", server.params["host"] ?: server.host)
            wsSettings.add("headers", headers)
            streamSettings.add("wsSettings", wsSettings)
        } else if (network == "grpc") {
            val grpcSettings = JsonObject()
            grpcSettings.addProperty("serviceName", server.params["serviceName"] ?: "")
            streamSettings.add("grpcSettings", grpcSettings)
        }
        
        proxyOutbound.add("streamSettings", streamSettings)
        
        if (mux) {
            val muxObj = JsonObject()
            muxObj.addProperty("enabled", true)
            proxyOutbound.add("mux", muxObj)
        }

        outbounds.add(proxyOutbound)

        // Direct Outbound
        val directOutbound = JsonObject()
        directOutbound.addProperty("protocol", "freedom")
        directOutbound.addProperty("tag", "direct")
        outbounds.add(directOutbound)

        // DNS Outbound
        val dnsOutbound = JsonObject()
        dnsOutbound.addProperty("protocol", "dns")
        dnsOutbound.addProperty("tag", "dns-out")
        outbounds.add(dnsOutbound)

        config.add("outbounds", outbounds)

        // Routing
        val routing = JsonObject()
        routing.addProperty("domainStrategy", "IPIfNonMatch")
        val rules = JsonArray()
        
        // DNS rule
        val dnsRule = JsonObject()
        dnsRule.addProperty("type", "field")
        dnsRule.addProperty("outboundTag", "dns-out")
        dnsRule.addProperty("port", 53)
        rules.add(dnsRule)

        // Routing Mode specific rules
        if (routingMode == "BYPASS_LAN_RU") {
            val lanRule = JsonObject()
            lanRule.addProperty("type", "field")
            lanRule.addProperty("outboundTag", "direct")
            val lanIp = JsonArray()
            lanIp.add("10.0.0.0/8")
            lanIp.add("172.16.0.0/12")
            lanIp.add("192.168.0.0/16")
            lanIp.add("fc00::/7")
            lanIp.add("fe80::/10")
            lanRule.add("ip", lanIp)
            rules.add(lanRule)
            
            val russiaRule = JsonObject()
            russiaRule.addProperty("type", "field")
            russiaRule.addProperty("outboundTag", "direct")
            
            val russiaDomains = JsonArray()
            // Основные российские домены (покрывает 95% ресурсов)
            russiaDomains.add("domain:.ru")
            russiaDomains.add("domain:.su")
            russiaDomains.add("domain:.xn--p1ai") // .рф
            russiaDomains.add("domain:.vk.com")
            russiaDomains.add("domain:.yandex.ru")
            russiaDomains.add("domain:.mail.ru")
            russiaRule.add("domain", russiaDomains)
            
            val russiaIp = JsonArray()
            // Основные диапазоны IP РФ (упрощенно)
            russiaIp.add("45.0.0.0/8")
            russiaIp.add("77.0.0.0/8")
            russiaIp.add("80.0.0.0/8")
            russiaIp.add("91.0.0.0/8")
            russiaIp.add("95.0.0.0/8")
            russiaIp.add("178.0.0.0/8")
            russiaIp.add("185.0.0.0/8")
            russiaIp.add("188.0.0.0/8")
            russiaIp.add("213.0.0.0/8")
            russiaRule.add("ip", russiaIp)

            rules.add(russiaRule)
        }


        routing.add("rules", rules)
        config.add("routing", routing)

        // DNS
        val dnsObj = JsonObject()
        val dnsServers = JsonArray()
        
        val googleDns = JsonObject()
        googleDns.addProperty("address", dns)
        googleDns.addProperty("port", 53)
        dnsServers.add(googleDns)

        dnsObj.add("servers", dnsServers)
        config.add("dns", dnsObj)

        return gson.toJson(config)
    }
}
