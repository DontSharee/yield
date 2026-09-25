package me.dontshare.yieldloadtest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.atomic.LongAdder;

/**
 * The far end of every bot's connection: counts what the server would have
 * sent the client, then throws it away.
 * <p>
 * Two kinds of traffic arrive. The plugins' own packets come through
 * PacketEvents already encoded, so their exact size is known. The server's
 * own packets arrive as unencoded packet objects - a bot's channel has no
 * encoder - so they are counted, not weighed. Anything else (the pipeline
 * reconfiguration tasks the server sends when a player joins) is simply
 * acknowledged, which is all the server waits for.
 */
final class TrafficSink extends ChannelOutboundHandlerAdapter {

    static final LongAdder PLUGIN_PACKETS = new LongAdder();
    static final LongAdder PLUGIN_BYTES = new LongAdder();
    static final LongAdder VANILLA_PACKETS = new LongAdder();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            if (msg instanceof ByteBuf buffer) {
                PLUGIN_PACKETS.increment();
                PLUGIN_BYTES.add(buffer.readableBytes());
            } else if (msg.getClass().getName().startsWith("net.minecraft.network.protocol.")) {
                VANILLA_PACKETS.increment();
            }
        } finally {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        // Nothing buffered - everything was dropped in write.
    }
}
