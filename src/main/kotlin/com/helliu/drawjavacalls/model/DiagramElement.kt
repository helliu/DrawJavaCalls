package com.helliu.drawjavacalls.model

class DiagramElement(
    val id: String = java.util.UUID.randomUUID().toString(),
    var filePath: String,
    var methodName: String,
    var group: String? = null
) {
    fun getIdentifier(): String {
        val fileName = filePath.substringAfterLast('\\').substringAfterLast('/')
        val stateName = fileName.replace(".", "_")
        return if (group.isNullOrBlank()) {
            "$stateName.$methodName"
        } else {
            "$group.$stateName.$methodName"
        }
    }
}
