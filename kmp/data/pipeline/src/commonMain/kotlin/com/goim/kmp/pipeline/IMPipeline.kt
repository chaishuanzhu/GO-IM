package com.goim.kmp.pipeline

interface IMHandler {
    val name: String
}

interface InboundHandler : IMHandler {
    suspend fun channelRead(ctx: HandlerContext, msg: Any)
}

interface OutboundHandler : IMHandler {
    suspend fun write(ctx: HandlerContext, msg: Any)
}

class HandlerContext internal constructor(
    val handler: IMHandler,
    private val pipeline: IMPipeline,
) {
    internal var nextInbound: HandlerContext? = null
    internal var nextOutbound: HandlerContext? = null

    /** 交给下一个入站 Handler；已是末尾则丢弃。 */
    suspend fun fireChannelRead(msg: Any) {
        val next = nextInbound ?: return
        (next.handler as InboundHandler).channelRead(next, msg)
    }

    /** 交给下一个出站 Handler；已是末尾则写入 Transport。 */
    suspend fun write(msg: Any) {
        val next = nextOutbound ?: return pipeline.writeToTransport(msg)
        (next.handler as OutboundHandler).write(next, msg)
    }
}

/**
 * Netty 风格 Pipeline（与 iOS `IMPipeline` 同构）。
 *
 * 入站按添加顺序经过 [InboundHandler]；出站按添加的**逆序**经过 [OutboundHandler]，最终 `ByteArray` 交给 [transportWriter]。
 * 调用方负责串行化（`ConnectionRepositoryImpl` 在单线程调度器上驱动）。
 */
class IMPipeline(private val transportWriter: suspend (ByteArray) -> Unit) {
    private val contexts = mutableListOf<HandlerContext>()
    private var headInbound: HandlerContext? = null
    private var headOutbound: HandlerContext? = null

    val names: List<String> get() = contexts.map { it.handler.name }

    fun addLast(handler: IMHandler): IMPipeline {
        contexts += HandlerContext(handler, this)
        relink()
        return this
    }

    fun addAfter(name: String, handler: IMHandler): IMPipeline {
        val index = contexts.indexOfFirst { it.handler.name == name }
        require(index >= 0) { "handler '$name' not found" }
        contexts.add(index + 1, HandlerContext(handler, this))
        relink()
        return this
    }

    suspend fun fireChannelRead(msg: Any) {
        val head = headInbound ?: return
        (head.handler as InboundHandler).channelRead(head, msg)
    }

    suspend fun write(msg: Any) {
        val head = headOutbound ?: return writeToTransport(msg)
        (head.handler as OutboundHandler).write(head, msg)
    }

    internal suspend fun writeToTransport(msg: Any) {
        if (msg is ByteArray) transportWriter(msg)
    }

    private fun relink() {
        val inbound = contexts.filter { it.handler is InboundHandler }
        inbound.forEachIndexed { i, ctx -> ctx.nextInbound = inbound.getOrNull(i + 1) }
        headInbound = inbound.firstOrNull()

        val outbound = contexts.filter { it.handler is OutboundHandler }.asReversed()
        outbound.forEachIndexed { i, ctx -> ctx.nextOutbound = outbound.getOrNull(i + 1) }
        headOutbound = outbound.firstOrNull()
    }
}
