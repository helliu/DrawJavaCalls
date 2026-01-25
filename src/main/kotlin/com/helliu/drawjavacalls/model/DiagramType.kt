package com.helliu.drawjavacalls.model

enum class DiagramType(val displayName: String) {
    PLANT_UML("PlantUml"),
    MERMAID("Mermaid");

    override fun toString(): String = displayName

    companion object {
        fun fromDisplayName(name: String): DiagramType {
            return values().find { it.displayName == name } ?: PLANT_UML
        }
    }
}
