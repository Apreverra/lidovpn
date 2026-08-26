package com.lido.vpn

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.File

object XrayConfigGenerator {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generateConfig(
        server: VpnServer,
        dns: String,
        sniffing: Boolean,
        mux: Boolean,
        routingMode: String = "GLOBAL",
        @Suppress("UNUSED_PARAMETER") mtu: Int = 1500,
        assetPath: String = "",
        utlsFingerprint: String = "chrome",
        isTestConfig: Boolean = false,
        socksProxyPort: Int = 0,
        socksInboundPort: Int = 10808,
    ): String {
        val config = JsonObject()
        config.add("log", createLog())

        val inbounds = JsonArray()
        val outbounds = JsonArray()
        val rules = JsonArray()

        val sniffingObj = createSniffing(sniffing)

        if (!isTestConfig) {
            inbounds.add(createTunInbound(mtu, sniffingObj))
            inbounds.add(createSocksInbound(socksInboundPort, "socks-in", sniffingObj))
        } else {
            inbounds.add(createSocksInbound(socksInboundPort, null, null))
        }
        config.add("inbounds", inbounds)

        if (socksProxyPort > 0) {
            outbounds.add(createSocksOutbound("proxy", "127.0.0.1", socksProxyPort))
        } else {
            outbounds.add(buildMainOutbound(server, mux, utlsFingerprint))
        }

        outbounds.add(createProtocolOutbound("freedom", "direct"))
        outbounds.add(createProtocolOutbound("dns", "dns-out"))
        config.add("outbounds", outbounds)

        // Routing
        rules.add(createFieldRule(outboundTag = "dns-out", port = 53))

        if (routingMode == "BYPASS_LAN_RU") {
            rules.add(createFieldRule(outboundTag = "direct", ip = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")))
            
            rules.add(createFieldRule(
                outboundTag = "proxy",
                domain = listOf("domain:t.me", "domain:tdesktop.com", "domain:telegram.org", "domain:telegram.me", "domain:telegra.ph", "domain:telegram.dog")
            ))

            val hasGeoIp = if (assetPath.isNotEmpty()) File(assetPath, "geoip.dat").exists() else false
            val hasGeoSite = if (assetPath.isNotEmpty()) File(assetPath, "geosite.dat").exists() else false

            if (hasGeoIp) rules.add(createFieldRule(outboundTag = "direct", ip = listOf("geoip:geoip.dat:ru")))
            if (hasGeoSite) rules.add(createFieldRule(outboundTag = "direct", domain = listOf("geosite:geosite.dat:ru")))
        }

        rules.add(createFieldRule(outboundTag = "proxy", network = "udp,tcp"))
        
        config.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", rules)
        })

        config.add("dns", createDns(dns))

        return gson.toJson(config)
    }

    fun generateByeDpiBridgeConfig(
        byeDpiAddress: String = "127.0.0.1",
        byeDpiPort: Int,
        dns: String,
        mtu: Int
    ): String {
        return JsonObject().apply {
            add("log", createLog())
            add("inbounds", JsonArray().apply { add(createTunInbound(mtu, null)) })
            add("outbounds", JsonArray().apply {
                add(createSocksOutbound("proxy", byeDpiAddress, byeDpiPort))
                add(createProtocolOutbound("dns", "dns-out"))
            })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "IPIfNonMatch")
                add("rules", JsonArray().apply {
                    add(createFieldRule(outboundTag = "dns-out", port = 53))
                    add(createFieldRule(outboundTag = "proxy", network = "tcp,udp"))
                })
            })
            add("dns", createDns(dns))
        }.let { gson.toJson(it) }
    }

    // --- Helpers ---

    private fun createLog() = JsonObject().apply {
        addProperty("loglevel", "error")
    }

    private fun createSniffing(enabled: Boolean) = JsonObject().apply {
        addProperty("enabled", enabled)
        add("destOverride", JsonArray().apply { add("http"); add("tls") })
    }

    private fun createDns(dns: String) = JsonObject().apply {
        addProperty("domainStrategy", "AsIs")
        add("servers", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("address", dns)
                addProperty("port", 53)
            })
        })
    }

    private fun createTunInbound(mtu: Int, sniffing: JsonObject?) = JsonObject().apply {
        addProperty("protocol", "tun")
        addProperty("tag", "tun-in")
        add("settings", JsonObject().apply {
            addProperty("mtu", mtu)
            addProperty("stack", "gvisor")
        })
        sniffing?.let { add("sniffing", it) }
    }

    private fun createSocksInbound(port: Int, tag: String?, sniffing: JsonObject?) = JsonObject().apply {
        addProperty("protocol", "socks")
        addProperty("port", port)
        tag?.let { addProperty("tag", it) }
        add("settings", JsonObject().apply { addProperty("udp", true) })
        sniffing?.let { add("sniffing", it) }
    }

    private fun createProtocolOutbound(protocol: String, tag: String) = JsonObject().apply {
        addProperty("protocol", protocol)
        addProperty("tag", tag)
    }

    private fun createSocksOutbound(tag: String, host: String, port: Int) = JsonObject().apply {
        addProperty("protocol", "socks")
        addProperty("tag", tag)
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                })
            })
        })
    }

    private fun createFieldRule(
        outboundTag: String,
        port: Int? = null,
        ip: List<String>? = null,
        domain: List<String>? = null,
        inboundTag: List<String>? = null,
        network: String? = null
    ) = JsonObject().apply {
        addProperty("type", "field")
        addProperty("outboundTag", outboundTag)
        port?.let { addProperty("port", it) }
        network?.let { addProperty("network", it) }
        ip?.let { list -> add("ip", JsonArray().apply { list.forEach { add(it) } }) }
        domain?.let { list -> add("domain", JsonArray().apply { list.forEach { add(it) } }) }
        inboundTag?.let { list -> add("inboundTag", JsonArray().apply { list.forEach { add(it) } }) }
    }

    private fun buildMainOutbound(server: VpnServer, mux: Boolean, utlsFingerprint: String): JsonObject {
        val proxyOutbound = JsonObject()
        val protocol = server.type.lowercase().let {
            when(it) {
                "ss" -> "shadowsocks"
                "socks5", "socks4" -> "socks"
                "hysteria2", "hy2" -> "hysteria2"
                "tuic" -> "tuic"
                else -> it
            }
        }
        
        proxyOutbound.addProperty("protocol", protocol)
        proxyOutbound.addProperty("tag", "proxy")
        
        val settings = JsonObject()
        when (protocol) {
            "vless" -> {
                settings.add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", server.uuid)
                                addProperty("encryption", "none")
                                server.params["flow"]?.takeIf { it.isNotEmpty() }?.let { addProperty("flow", it) }
                            })
                        })
                    })
                })
            }
            "vmess" -> {
                settings.add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", server.uuid)
                                addProperty("security", server.params["scy"] ?: "auto")
                                addProperty("alterId", server.params["aid"]?.toIntOrNull() ?: 0)
                            })
                        })
                    })
                })
            }
            "trojan" -> {
                settings.add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        addProperty("password", server.uuid)
                    })
                })
            }
            "shadowsocks" -> {
                settings.add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        if (server.uuid.contains(":")) {
                            server.uuid.split(":").let {
                                addProperty("method", it[0])
                                addProperty("password", it[1])
                            }
                        } else {
                            addProperty("method", "aes-256-gcm")
                            addProperty("password", server.uuid)
                        }
                    })
                })
            }
            "socks" -> {
                settings.add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        addProperty("version", server.params["version"] ?: "5")
                        if (server.uuid.isNotEmpty()) {
                            add("users", JsonArray().apply {
                                add(JsonObject().apply {
                                    if (server.uuid.contains(":")) {
                                        server.uuid.split(":").let {
                                            addProperty("user", it[0])
                                            addProperty("pass", it[1])
                                        }
                                    } else {
                                        addProperty("user", server.uuid)
                                    }
                                })
                            })
                        }
                    })
                })
            }
            "hysteria2" -> {
                settings.add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        addProperty("password", server.uuid)
                    })
                })
            }
            "tuic" -> {
                settings.add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", server.host)
                        addProperty("port", server.port)
                        addProperty("uuid", server.uuid)
                        addProperty("password", server.params["pass"] ?: "")
                        addProperty("congestionControl", server.params["congestion_control"] ?: "bbr")
                        addProperty("udpRelayMode", "native")
                    })
                })
            }
        }
        proxyOutbound.add("settings", settings)

        val streamSettings = JsonObject().apply {
            val defaultNet = when (protocol) {
                "hysteria2", "tuic" -> "udp"
                else -> "tcp"
            }
            val defaultSec = when (protocol) {
                "hysteria2", "tuic" -> "tls"
                else -> "none"
            }

            val network = server.params["net"] ?: server.params["type"] ?: defaultNet
            addProperty("network", network)
            
            when (network) {
                "ws" -> {
                    add("wsSettings", JsonObject().apply {
                        addProperty("path", server.params["path"] ?: "/")
                        server.params["host"]?.takeIf { it.isNotEmpty() }?.let { 
                            addProperty("host", it)
                            add("headers", JsonObject().apply { addProperty("Host", it) })
                        }
                    })
                }
                "grpc" -> {
                    add("grpcSettings", JsonObject().apply {
                        addProperty("serviceName", server.params["serviceName"] ?: server.params["path"] ?: "")
                    })
                }
                "h2", "http" -> {
                    add("httpSettings", JsonObject().apply {
                        addProperty("path", server.params["path"] ?: "/")
                        add("host", JsonArray().apply {
                            server.params["host"]?.split(",")?.forEach { add(it.trim()) }
                        })
                    })
                }
            }
            
            val security = server.params["security"] ?: defaultSec
            addProperty("security", security)
            
            if (security == "tls" || security == "reality") {
                val tlsObj = JsonObject().apply {
                    addProperty("serverName", server.params["sni"] ?: server.host)
                    addProperty("fingerprint", server.params["fp"] ?: utlsFingerprint)
                    if (server.params["insecure"] == "true") addProperty("allowInsecure", true)
                    server.params["alpn"]?.takeIf { it.isNotEmpty() }?.let { alpn ->
                        add("alpn", JsonArray().apply { alpn.split(",").forEach { add(it.trim()) } })
                    }
                    if (security == "reality") {
                        addProperty("publicKey", server.params["pbk"] ?: "")
                        addProperty("shortId", server.params["sid"] ?: "")
                    }
                }
                add(if (security == "reality") "realitySettings" else "tlsSettings", tlsObj)
            }
        }
        proxyOutbound.add("streamSettings", streamSettings)
        
        if (mux && protocol != "shadowsocks") {
            proxyOutbound.add("mux", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("concurrency", 8)
            })
        }
        
        return proxyOutbound
    }
}
