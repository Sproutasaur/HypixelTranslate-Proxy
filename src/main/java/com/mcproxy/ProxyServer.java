package com.mcproxy;

import com.mcproxy.auth.AuthService;
import com.mcproxy.chat.Translator;
import com.mcproxy.proto.ClientHandler;
import com.mcproxy.proto.FrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

/** "代理的服务器"那一端：监听本地端口，每个游戏客户端一个会话。 */
public final class ProxyServer {

    private ProxyServer() {}

    public static void start(Config cfg, AuthService auth, Translator translator) throws InterruptedException {
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 16)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            Session session = new Session(cfg, auth, translator, ch);
                            ClientHandler handler = new ClientHandler(session);
                            session.setClientHandler(handler);

                            ch.pipeline().addLast(new FrameCodec());
                            ch.pipeline().addLast(new IdleStateHandler(0, 0, cfg.idleTimeoutSeconds));
                            ch.pipeline().addLast(handler);
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                    session.closeAll();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    session.closeAll();
                                }

                                @Override
                                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                    if (evt instanceof IdleStateEvent) {
                                        session.closeAll();
                                        return;
                                    }
                                    ctx.fireUserEventTriggered(evt);
                                }
                            });
                        }
                    });

            Channel channel = bootstrap.bind(cfg.listenHost, cfg.listenPort).sync().channel();

            System.out.println();
            System.out.println("  HypixelTranslate-Proxy 已启动");
            System.out.println("  监听:      " + cfg.listenHost + ":" + cfg.listenPort
                    + "   (游戏内用「直接连接」填这个)");
            System.out.println("  上游:      " + cfg.upstreamHost + ":" + cfg.upstreamPort);
            System.out.println("  握手域名:  " + cfg.handshakeHost + "   (Hypixel 强路由认这个)");
            System.out.println("  账号:      " + auth.name());
            System.out.println("  翻译:      " + (cfg.translate.enabled
                    ? cfg.translate.targetLanguage + " via " + cfg.translate.model : "关闭"));
            System.out.println("  Tab 统计:  " + (cfg.tab.enabled
                    ? cfg.tab.stat + " · 数据源 " + cfg.tab.dataSource : "关闭"));
            System.out.println();

            channel.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
