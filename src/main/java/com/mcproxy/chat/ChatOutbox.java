package com.mcproxy.chat;

import com.mcproxy.proto.Buf;
import com.mcproxy.proto.Ids;
import com.mcproxy.proto.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天出口队列：保证"等译文"这件事既有序、又不会无限期拖住聊天。
 *
 * 为什么需要它：译文必须和原消息绑定——悬停模式要把译文写进消息组件里，
 * 追加模式要让译文紧跟在原文下一行。消息一旦发出去就改不了了，
 * 所以只能按到达顺序等译文，最多等 timeoutMs，超时就先发原文。
 * 追加模式下译文晚到不会丢，会单独补一行。
 */
public final class ChatOutbox {

    private static final class Entry {
        final long seq;
        final String json;
        final int position;
        final long deadline;
        String translation;

        Entry(long seq, String json, int position, long deadline) {
            this.seq = seq;
            this.json = json;
            this.position = position;
            this.deadline = deadline;
        }
    }

    private final Channel channel;
    private final long timeoutMs;
    private final Deque<Entry> queue = new ArrayDeque<>();
    /** 原文已经发出去了、译文才回来的（追加模式专用），攒着补发 */
    private final Map<Long, String> pendingLate = new LinkedHashMap<>();
    private final AtomicBoolean timerArmed = new AtomicBoolean(false);

    private long nextSeq;

    public ChatOutbox(Channel channel, long timeoutMs) {
        this.channel = channel;
        this.timeoutMs = Math.max(timeoutMs, 50);
    }

    /** 进队并返回序号；调用方拿这个序号去认领译文。 */
    public long offer(String json, int position) {
        long seq;
        synchronized (queue) {
            seq = nextSeq++;
            queue.addLast(new Entry(seq, json, position, System.currentTimeMillis() + timeoutMs));
        }
        armTimer();
        return seq;
    }

    /** 译文回来了（可能在别的线程）。 */
    public void complete(long seq, String translation) {
        if (translation == null || translation.isEmpty()) return;
        synchronized (queue) {
            boolean found = false;
            for (Entry e : queue) {
                if (e.seq == seq) {
                    e.translation = translation;
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 原文已经发出去了（等超时了）：补一行，位置在最新的地方，不保证紧贴原文。
                pendingLate.put(seq, translation);
                if (pendingLate.size() > 32) {
                    Iterator<Long> it = pendingLate.keySet().iterator();
                    if (it.hasNext()) { it.next(); it.remove(); }
                }
            }
        }
        channel.eventLoop().execute(this::flush);
    }

    private void armTimer() {
        if (timerArmed.compareAndSet(false, true)) {
            channel.eventLoop().schedule(this::tick, 20, TimeUnit.MILLISECONDS);
        }
    }

    private void tick() {
        timerArmed.set(false);
        flush();
        boolean more;
        synchronized (queue) {
            more = !queue.isEmpty() || !pendingLate.isEmpty();
        }
        if (more) armTimer();
    }

    /** 只在 event loop 线程执行。 */
    private void flush() {
        while (true) {
            Entry head;
            synchronized (queue) {
                head = queue.peekFirst();
                if (head == null) break;
                if (head.translation == null && System.currentTimeMillis() < head.deadline) {
                    break;   // 还在等，等超时或译文回来
                }
                queue.pollFirst();
            }
            emit(head);
        }
        flushLate();
    }

    private void emit(Entry e) {
        boolean hasTranslation = e.translation != null && !e.translation.isEmpty();

        // 先发原文，紧接着发译文行，顺序固定，不会互相穿插。
        // 译文固定用 CHAT 位置：1.8.9 里 position=1(SYSTEM) 走的是"固定行号"通道，
        // 新消息会先把上一条同位置的删掉——原文若是系统消息，就会和译文互相顶掉，
        // 看起来就是译文跑到上面、或者原文消失。用 CHAT 位置是纯新增一行，互不影响。
        writeChat(e.json, e.position);
        if (hasTranslation) writeTranslationLine(e.translation);
    }

    /** 超时之后才回来的译文：单独补一行，至少不丢。 */
    private void flushLate() {
        List<String> late;
        synchronized (queue) {
            if (pendingLate.isEmpty()) return;
            late = new ArrayList<>(pendingLate.values());
            pendingLate.clear();
        }
        for (String t : late) writeTranslationLine(t);
    }

    private void writeTranslationLine(String translation) {
        String text = "└ " + translation;
        if (text.length() > 240) text = text.substring(0, 240) + "…";
        writeChat(ChatText.toComponent(text, "gray"), Ids.CHAT_POSITION_CHAT);
    }

    private void writeChat(String json, int position) {
        ByteBuf out = channel.alloc().buffer();
        VarInt.write(out, Ids.PLAY_CHAT_CLIENTBOUND);
        Buf.writeString(out, json);
        out.writeByte(position);
        channel.writeAndFlush(out);
    }
}
