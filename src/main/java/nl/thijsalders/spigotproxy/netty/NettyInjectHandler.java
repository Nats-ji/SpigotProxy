package nl.thijsalders.spigotproxy.netty;

import io.netty.channel.ChannelHandlerContext;

public interface NettyInjectHandler {

	void packetReceived(NettyDecoderHandler handler, ChannelHandlerContext context, Object object) throws Exception;

	boolean isEnabled();
	
}
