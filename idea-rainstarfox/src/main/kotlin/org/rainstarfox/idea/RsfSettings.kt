package org.rainstarfox.idea

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * 插件的全局设置（Setting → Tools → rainStarfox）。
 *
 * 只有一件事必须让用户能改：**编译器在哪**。插件自己不打包编译器——`rsf` 有 jar、
 * 有原生 exe、还可能装在 PATH 里，硬编码任何一种都是给用户找麻烦，所以留一个
 * 手填的口子，其余情况靠 [RsfLauncher] 自动探测。
 */
@Service(Service.Level.APP)
@State(name = "RsfSettings", storages = [Storage("rainstarfox.xml")])
class RsfSettings : PersistentStateComponent<RsfSettings.State> {

    data class State(
        /** 手动指定的启动器：可以是 rainStarfox.exe，也可以是 .jar。留空则自动探测。 */
        var launcher: String = "",
        /** 跑 .jar 用哪个 java；留空则用 JAVA_HOME 或 PATH 里的 java。 */
        var javaCommand: String = "",
        /** 边打字边检查（关掉之后只在你手动触发时才跑编译器）。 */
        var liveDiagnostics: Boolean = true,
        /** 代码补全。 */
        var completion: Boolean = true,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): RsfSettings = service()
    }
}
