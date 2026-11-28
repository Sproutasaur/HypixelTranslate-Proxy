package com.mcproxy.tab;

import com.mcproxy.proto.Buf;
import com.mcproxy.proto.VarInt;
import io.netty.buffer.ByteBuf;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头顶名牌（nametag）颜色。
 *
 * 有些模式既不给队伍色，也不在 Tab 的 displayName 里带颜色，名字就白了。
 * 客户端画头顶名牌时用的是实体元数据里的 Custom Name（index 2），
 * 那里通常带 § 色码，正好能把 Tab 缺的颜色补回来。
 */
public final class Nametags {

    private final Map<String, Integer> uuidToEntity = new ConcurrentHashMap<>();
    private final Map<Integer, String> entityName = new ConcurrentHashMap<>();
    private volatile Runnable changeListener;

    /** 名牌色变化后通知 Tab 重刷，晚到的颜色也能补上。 */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        Runnable l = changeListener;
        if (l == null) return;
        try {
            l.run();
        } catch (Exception ignored) {
        }
    }

    public static String key(byte[] uuid) {
        return uuid == null || uuid.length != 16 ? null
                : Base64.getEncoder().withoutPadding().encodeToString(uuid);
    }

    /** 0x0C Spawn Player：EntityID + UUID + 位置/朝向/手持物 + 元数据。 */
    public void onSpawnPlayer(ByteBuf b) {
        try {
            int entityId = VarInt.read(b);
            byte[] uuid = new byte[16];
            b.readBytes(uuid);
            String k = key(uuid);
            if (k != null && uuidToEntity.put(k, entityId) == null) notifyChanged();

            b.skipBytes(4 + 4 + 4);                 // X / Y / Z（定点 int）
            b.skipBytes(1 + 1 + 2);                 // yaw / pitch / 手持物
            readMetadata(b, entityId);              // 同一包里也可能带 Custom Name
        } catch (Exception ignored) {
            // 解析失败只影响配色，绝不影响原包转发
        }
    }

    /** 0x1C Entity Metadata。 */
    public void onMetadata(ByteBuf b) {
        try {
            int entityId = VarInt.read(b);
            readMetadata(b, entityId);
        } catch (Exception ignored) {
        }
    }

    private void readMetadata(ByteBuf b, int entityId) {
        while (b.readableBytes() > 0) {
            int header = b.readByte();
            if (header == 0x7F) return;
            int index = header & 0x1F;
            int type = (header & 0xE0) >> 5;
            if (index == 2 && type == 4) {
                String name = Buf.readString(b);
                boolean changed = name.isEmpty()
                        ? entityName.remove(entityId) != null
                        : !name.equals(entityName.put(entityId, name));
                if (changed) notifyChanged();
                continue;
            }
            if (!skipValue(b, type)) return;        // 认不出的类型就停，保留上次结果
        }
    }

    private static boolean skipValue(ByteBuf b, int type) {
        switch (type) {
            case 0 -> b.skipBytes(1);                       // byte
            case 1 -> b.skipBytes(2);                       // short
            case 2 -> b.skipBytes(4);                       // int
            case 3 -> b.skipBytes(4);                       // float
            case 4 -> Buf.readString(b);                    // string
            case 5 -> {                                     // item stack
                short item = b.readShort();
                if (item < 0) return true;
                if (b.readByte() >= 0) return false;        // 带 NBT 就放弃解析
                b.skipBytes(2);
            }
            case 6 -> b.skipBytes(12);                      // 3 × int
            case 7 -> b.skipBytes(12);                      // 3 × float
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 该玩家头顶名牌里的第一个颜色码（如 §c）；没有就 null。 */
    public String colorCode(byte[] uuid) {
        String k = key(uuid);
        if (k == null) return null;
        Integer entityId = uuidToEntity.get(k);
        if (entityId == null) return null;
        return firstColor(entityName.get(entityId));
    }

    /** 该玩家头顶名牌原文，调试用。 */
    public String rawName(byte[] uuid) {
        String k = key(uuid);
        if (k == null) return null;
        Integer entityId = uuidToEntity.get(k);
        return entityId == null ? null : entityName.get(entityId);
    }

    private static String firstColor(String s) {
        if (s == null) return null;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '§') continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) return "§" + c;
        }
        return null;
    }

    public String stats() {
        return "实体=" + entityName.size() + " 绑定=" + uuidToEntity.size();
    }
}
