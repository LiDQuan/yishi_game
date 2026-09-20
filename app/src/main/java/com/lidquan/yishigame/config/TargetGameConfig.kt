package com.lidquan.yishigame.config

data class TargetGameConfig(val packageName: String?) {
    companion object {
        private val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun parse(raw: String?): TargetGameConfig {
            val value = raw?.trim().orEmpty()
            return TargetGameConfig(value.takeIf(packagePattern::matches))
        }
    }
}
