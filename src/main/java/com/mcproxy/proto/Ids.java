package com.mcproxy.proto;

/** 1.8.9（协议 47）里用到的包 id。 */
public final class Ids {

    private Ids() {}

    // 状态：Handshaking / Login 都是 0x00，靠状态区分
    public static final int HANDSHAKE = 0x00;
    public static final int LOGIN_START = 0x00;

    // 客户端收到的 Login 阶段包
    public static final int DISCONNECT_LOGIN = 0x00;
    public static final int ENCRYPTION_REQUEST = 0x01;
    public static final int LOGIN_SUCCESS = 0x02;
    public static final int SET_COMPRESSION = 0x03;

    // 客户端发出的 Login 阶段包
    public static final int ENCRYPTION_RESPONSE = 0x01;

    // Play 阶段
    public static final int PLAY_CHAT_CLIENTBOUND = 0x02;   // JSON + position
    public static final int PLAY_CHAT_SERVERBOUND = 0x01;   // 玩家发的聊天

    // position 字节：0 = 聊天框，1 = 系统消息，2 = 物品栏上方
    public static final int CHAT_POSITION_CHAT = 0;
    public static final int CHAT_POSITION_SYSTEM = 1;
    public static final int CHAT_POSITION_ABOVE_HOTBAR = 2;

    // Tab 相关
    public static final int PLAY_PLAYER_LIST_ITEM = 0x38;
    public static final int PLAY_TEAMS = 0x3E;
    public static final int PLAY_HEADER_FOOTER = 0x47;
}
