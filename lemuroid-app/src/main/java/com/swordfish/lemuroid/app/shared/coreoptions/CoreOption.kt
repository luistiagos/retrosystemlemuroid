package com.swordfish.lemuroid.app.shared.coreoptions

import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.libretrodroid.Variable
import java.io.Serializable

data class CoreOption(
    val variable: CoreVariable,
    val name: String,
    val optionValues: List<String>,
) : Serializable {
    companion object {
        fun fromLibretroDroidVariable(variable: Variable): CoreOption {
            val name = variable.description?.split(";")?.getOrNull(0)
                ?: throw IllegalArgumentException("Invalid core variable description: ${variable.description}")
            val values = variable.description?.split(";")?.getOrNull(1)?.trim()?.split('|') ?: listOf()
            val coreVariable = CoreVariable(
                variable.key ?: throw IllegalArgumentException("Core variable key is null"),
                variable.value ?: throw IllegalArgumentException("Core variable value is null"),
            )
            return CoreOption(coreVariable, name, values)
        }
    }
}
