package com.meshnet.meshnet_app.localnet.rbac

enum class Role(val level: Int, val label: String) {
    OWNER(100, "Owner"),
    ADMIN(80, "Admin"),
    MODERATOR(60, "Moderator"),
    MEMBER(40, "Member"),
    GUEST(20, "Guest"),
    BANNED(0, "Banned");

    companion object {
        fun fromLevel(level: Int): Role = values().firstOrNull { it.level >= level } ?: GUEST
        fun fromString(str: String): Role = values().firstOrNull { it.name.equals(str, ignoreCase = true) } ?: GUEST
    }
}