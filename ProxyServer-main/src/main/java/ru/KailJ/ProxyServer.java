package ru.KailJ;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class ProxyServer {
    private static String host;
    private static int port;
    private static String authUsername;
    private static String authPassword;

    public static void main(String[] args) {
        loadConfig();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("httpServerCodec", new HttpServerCodec());
                            pipeline.addLast("httpAggregator", new HttpObjectAggregator(65536));
                            pipeline.addLast("proxyHandler", new ProxyServerHandler(authUsername, authPassword));
                        }
                    });

            System.out.println("Starting proxy server on " + host + ":" + port);
            ChannelFuture f = b.bind(host, port).sync();
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            System.err.println("Server interrupted: " + e.getMessage());
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void loadConfig() {
        File configFile = new File("config.yml");
        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }

        try (InputStream input = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(input);

            host = (String) config.getOrDefault("host", "0.0.0.0");
            port = (int) config.getOrDefault("port", 33526);

            if (!config.containsKey("auth_username") || !config.containsKey("auth_password")) {
                authUsername = generateRandomString(6);
                authPassword = generateRandomString(6);
                config.put("auth_username", authUsername);
                config.put("auth_password", authPassword);

                try (Writer writer = new FileWriter(configFile)) {
                    yaml.dump(config, writer);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to update configuration: " + e.getMessage(), e);
                }
            } else {
                authUsername = (String) config.get("auth_username");
                authPassword = (String) config.get("auth_password");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration: " + e.getMessage(), e);
        }
    }

    private static void createDefaultConfig(File configFile) {
        Map<String, Object> defaultConfig = new LinkedHashMap<>();
        defaultConfig.put("host", "0.0.0.0");
        defaultConfig.put("port", 33526);
        defaultConfig.put("auth_username", generateRandomString(6));
        defaultConfig.put("auth_password", generateRandomString(6));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        try (Writer writer = new FileWriter(configFile)) {
            yaml.dump(defaultConfig, writer);
            System.out.println("Default configuration created: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create default configuration: " + e.getMessage(), e);
        }
    }

    private static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}

class ProxyServerHandler extends SimpleChannelInboundHandler<HttpObject> {
    private final String authUsername;
    private final String authPassword;
    private Channel outboundChannel;

    ProxyServerHandler(String authUsername, String authPassword) {
        this.authUsername = authUsername;
        this.authPassword = authPassword;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("Client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("Client disconnected: " + ctx.channel().remoteAddress());
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            if (!isAuthorized(request)) {
                sendAuthRequiredResponse(ctx);
                return;
            }

            if (HttpMethod.CONNECT.equals(request.method())) {
                handleConnect(ctx, request);
            } else {
                System.out.println("Unsupported method: " + request.method());
                ctx.close();
            }
        }
    }

    private boolean isAuthorized(HttpRequest request) {
        String authHeader = request.headers().get("Proxy-Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        String base64Credentials = authHeader.substring("Basic ".length());

        ByteBuf encodedBuf = Unpooled.copiedBuffer(base64Credentials, CharsetUtil.US_ASCII);
        ByteBuf decodedBuf = Base64.decode(encodedBuf);
        byte[] decodedBytes = ByteBufUtil.getBytes(decodedBuf);
        decodedBuf.release();

        String credentials = new String(decodedBytes, CharsetUtil.UTF_8);
        String[] parts = credentials.split(":", 2);

        if (parts.length != 2) {
            return false;
        }

        String username = parts[0];
        String password = parts[1];

        return authUsername.equals(username) && authPassword.equals(password);
    }

    private void sendAuthRequiredResponse(ChannelHandlerContext ctx) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
        response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"Proxy\"");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void handleConnect(ChannelHandlerContext ctx, HttpRequest request) {
        String uri = request.uri();
        System.out.println("Received CONNECT request: " + uri);
        String[] hostPort = uri.split(":");
        if (hostPort.length != 2) {
            System.err.println("Invalid CONNECT request URI: " + uri);
            ctx.close();
            return;
        }

        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new RelayHandler(ctx.channel()));
                    }
                });

        ChannelFuture f = b.connect(new InetSocketAddress(host, port));
        outboundChannel = f.channel();
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
                        .addListener((ChannelFutureListener) channelFuture -> {
                            if (ctx.pipeline().get("httpServerCodec") != null) {
                                ctx.pipeline().remove("httpServerCodec");
                            }
                            if (ctx.pipeline().get("httpAggregator") != null) {
                                ctx.pipeline().remove("httpAggregator");
                            }
                            ctx.pipeline().addLast(new RelayHandler(outboundChannel));
                        });
            } else {
                System.err.println("Failed to connect to target server: " + future.cause().getMessage());
                ctx.close();
            }
        });
    }

    public static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Error: " + cause.getMessage());
        ctx.close();
    }
}

class RelayHandler extends ChannelInboundHandlerAdapter {
    private final Channel relayChannel;

    RelayHandler(Channel relayChannel) {
        this.relayChannel = relayChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        relayChannel.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ProxyServerHandler.closeOnFlush(relayChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Relay error: " + cause.getMessage());
        ProxyServerHandler.closeOnFlush(ctx.channel());
    }
}