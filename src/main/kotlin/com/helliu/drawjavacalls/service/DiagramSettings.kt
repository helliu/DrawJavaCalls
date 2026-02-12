package com.helliu.drawjavacalls.service

import com.helliu.drawjavacalls.model.DiagramType
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "DrawJavaCallsSettings", storages = [Storage("DrawJavaCallsSettings.xml")])
class DiagramSettings : PersistentStateComponent<DiagramSettings.State> {
    data class State(
        var diagramType: String = DiagramType.PLANT_UML.name,
        var useProjectRoot: Boolean = true,
        var customRootPath: String = "",
        var loadFromEditor: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var diagramType: DiagramType
        get() = try {
            DiagramType.valueOf(myState.diagramType)
        } catch (e: Exception) {
            DiagramType.PLANT_UML
        }
        set(value) {
            myState.diagramType = value.name
        }

    var useProjectRoot: Boolean
        get() = myState.useProjectRoot
        set(value) {
            myState.useProjectRoot = value
        }

    var customRootPath: String
        get() = myState.customRootPath
        set(value) {
            myState.customRootPath = value
        }

    var loadFromEditor: Boolean
        get() = myState.loadFromEditor
        set(value) {
            myState.loadFromEditor = value
        }

    companion object {
        fun getInstance(project: Project): DiagramSettings = project.service()
    }
}
