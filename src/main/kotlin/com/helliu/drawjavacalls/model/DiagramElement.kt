package com.helliu.drawjavacalls.model

class DiagramElement(
    val id: String = java.util.UUID.randomUUID().toString(),
    var filePath: String,
    var title: String,
    var group: String? = null,
    var linkReference: String = title
) {
    fun getIdentifier(): String {
        val fileName = filePath.substringAfterLast('\\').substringAfterLast('/')
        val stateName = fileName.replace(".", "_")
        return if (group.isNullOrBlank()) {
            "$stateName.$title"
        } else {
            "$group.$stateName.$title"
        }
    }
}
