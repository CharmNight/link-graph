package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.LinkGraphBundle
import com.charmnight.linkgraph.llm.LlmProviderPreset
import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * IDEA Settings/Preferences 中的 Link Graph 配置页。
 * 这里提供真正可用的配置入口，避免把 LLM 配置埋在代码或环境变量里。
 */
class LinkGraphSettingsConfigurable : SearchableConfigurable {
    /** 设置持久化服务，负责读取和写回配置快照。 */
    private val service: LinkGraphSettingsService
        get() = ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java)
    /** 远程 LLM 设置校验器。 */
    private val validator = RemoteLlmSettingsValidator()

    /** 配置页根面板。 */
    private var panel: JPanel? = null
    /** 是否启用 LLM 的勾选框。 */
    private var llmEnabledCheckBox: JBCheckBox? = null
    /** 供应商选择下拉框。 */
    private var providerComboBox: JComboBox<LlmProviderPreset>? = null
    /** 远程 endpoint 输入框。 */
    private var endpointField: JBTextField? = null
    /** API Key 密码输入框。 */
    private var apiKeyField: JBPasswordField? = null
    /** 模型名称输入框。 */
    private var modelField: JBTextField? = null
    /** 超时时间输入控件。 */
    private var timeoutSpinner: JSpinner? = null
    /** 温度参数输入控件。 */
    private var temperatureSpinner: JSpinner? = null
    /** 立即校验按钮。 */
    private var validateButton: JButton? = null
    /** 校验结果提示标签。 */
    private var validationStatusLabel: JBLabel? = null

    /** 返回当前配置页在 IDE 中的唯一 ID。 */
    override fun getId(): String = "com.charmnight.linkgraph.settings"

    /** 返回配置页展示名称。 */
    override fun getDisplayName(): String {
        return LinkGraphBundle.message("settings.link-graph.display-name")
    }

    /** 创建并初始化配置页 UI 组件。 */
    override fun createComponent(): JComponent {
        if (panel != null) {
            return panel!!
        }

        /** 是否启用 LLM 的复选框。 */
        llmEnabledCheckBox = JBCheckBox(LinkGraphBundle.message("settings.link-graph.llm-enabled"))
        /** LLM 供应商下拉框。 */
        providerComboBox = JComboBox(LlmProviderPresets.entries.toTypedArray())
        /** endpoint 文本框。 */
        endpointField = JBTextField()
        /** API Key 密码框。 */
        apiKeyField = JBPasswordField()
        /** 模型名称文本框。 */
        modelField = JBTextField()
        /** 超时设置控件。 */
        timeoutSpinner = JSpinner(
            SpinnerNumberModel(
                LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS,
                LinkGraphSettingsState.MIN_TIMEOUT_SECONDS,
                LinkGraphSettingsState.MAX_TIMEOUT_SECONDS,
                5,
            ),
        )
        /** 温度设置控件。 */
        temperatureSpinner = JSpinner(SpinnerNumberModel(LinkGraphSettingsState.DEFAULT_TEMPERATURE, 0.0, 1.0, 0.1))
        /** 手动触发校验的按钮。 */
        validateButton = JButton(LinkGraphBundle.message("settings.link-graph.validate"))
        /** 校验状态提示标签。 */
        validationStatusLabel = createHintLabel(LinkGraphBundle.message("settings.link-graph.validate.idle"))
        llmEnabledCheckBox?.addActionListener { refreshFieldEnabledStates() }
        providerComboBox?.addActionListener { refreshFieldEnabledStates() }
        validateButton?.addActionListener {
            /** 当前 UI 中整理出的设置快照的校验结果。 */
            val result = runValidation(currentState())
            updateValidationStatus(result)
        }
        providerComboBox?.toolTipText = LinkGraphBundle.message(
            "settings.link-graph.provider.hint",
            LlmProviderPresets.MOCK.toString(),
        )
        endpointField?.toolTipText = LinkGraphBundle.message("settings.link-graph.endpoint.hint")
        apiKeyField?.toolTipText = LinkGraphBundle.message("settings.link-graph.api-key.hint")
        modelField?.toolTipText = LinkGraphBundle.message(
            "settings.link-graph.model.hint",
            LinkGraphSettingsState.DEFAULT_MODEL,
        )
        timeoutSpinner?.toolTipText = LinkGraphBundle.message(
            "settings.link-graph.timeout.hint",
            LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS,
            LinkGraphSettingsState.MIN_TIMEOUT_SECONDS,
            LinkGraphSettingsState.MAX_TIMEOUT_SECONDS,
        )
        temperatureSpinner?.toolTipText = LinkGraphBundle.message(
            "settings.link-graph.temperature.hint",
            LinkGraphSettingsState.DEFAULT_TEMPERATURE,
        )

        /** 由 FormBuilder 组装出的主表单面板。 */
        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(LinkGraphBundle.message("settings.link-graph.description")))
            .addComponent(createHintLabel(LinkGraphBundle.message("settings.link-graph.entry-hint")))
            .addComponent(createHintLabel(LinkGraphBundle.message("settings.link-graph.behavior-hint")))
            .addComponent(llmEnabledCheckBox!!)
            .addComponent(createHintLabel(LinkGraphBundle.message("settings.link-graph.llm-enabled.hint")))
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.provider"), providerComboBox!!)
            .addComponent(
                createHintLabel(
                    LinkGraphBundle.message(
                        "settings.link-graph.provider.hint",
                        LlmProviderPresets.MOCK.toString(),
                    ),
                ),
            )
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.endpoint"), endpointField!!)
            .addComponent(createHintLabel(LinkGraphBundle.message("settings.link-graph.endpoint.hint")))
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.api-key"), apiKeyField!!)
            .addComponent(createHintLabel(LinkGraphBundle.message("settings.link-graph.api-key.hint")))
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.model"), modelField!!)
            .addComponent(
                createHintLabel(
                    LinkGraphBundle.message(
                        "settings.link-graph.model.hint",
                        LinkGraphSettingsState.DEFAULT_MODEL,
                    ),
                ),
            )
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.timeout"), timeoutSpinner!!)
            .addComponent(
                createHintLabel(
                    LinkGraphBundle.message(
                        "settings.link-graph.timeout.hint",
                        LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS,
                        LinkGraphSettingsState.MIN_TIMEOUT_SECONDS,
                        LinkGraphSettingsState.MAX_TIMEOUT_SECONDS,
                    ),
                ),
            )
            .addLabeledComponent(LinkGraphBundle.message("settings.link-graph.temperature"), temperatureSpinner!!)
            .addComponent(
                createHintLabel(
                    LinkGraphBundle.message(
                        "settings.link-graph.temperature.hint",
                        LinkGraphSettingsState.DEFAULT_TEMPERATURE,
                    ),
                ),
            )
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    validateButton?.let(::add)
                },
            )
            .addComponent(validationStatusLabel!!)
            .panel

        /** 根面板只负责承载表单并提供边界布局。 */
        panel = JPanel(BorderLayout()).apply {
            add(formPanel, BorderLayout.NORTH)
        }
        reset()
        return panel!!
    }

    /** 判断当前 UI 内容是否与持久化配置不同。 */
    override fun isModified(): Boolean {
        /** 已持久化的配置快照。 */
        val snapshot = service.snapshot()
        return currentState() != snapshot
    }

    /** 校验并保存当前 UI 中的配置。 */
    override fun apply() {
        /** 当前 UI 整理出的新配置。 */
        val nextState = currentState()
        /** 保存前先执行一次同步校验。 */
        val result = runValidation(nextState)
        if (!result.ok) {
            updateValidationStatus(result)
            throw ConfigurationException(result.message)
        }
        service.update(nextState)
        updateValidationStatus(result)
    }

    /** 用持久化配置重置当前 UI。 */
    override fun reset() {
        /** 已持久化的配置快照。 */
        val snapshot = service.snapshot()
        llmEnabledCheckBox?.isSelected = snapshot.llmEnabled
        providerComboBox?.selectedItem = snapshot.providerPreset()
        endpointField?.text = snapshot.normalizedEndpoint()
        apiKeyField?.text = snapshot.apiKey
        modelField?.text = snapshot.model
        timeoutSpinner?.value = snapshot.effectiveTimeoutSeconds()
        temperatureSpinner?.value = snapshot.effectiveTemperature()
        validationStatusLabel?.text = LinkGraphBundle.message("settings.link-graph.validate.idle")
        refreshFieldEnabledStates()
    }

    /** 释放配置页创建的 UI 资源。 */
    override fun disposeUIResources() {
        panel = null
        llmEnabledCheckBox = null
        providerComboBox = null
        endpointField = null
        apiKeyField = null
        modelField = null
        timeoutSpinner = null
        temperatureSpinner = null
        validateButton = null
        validationStatusLabel = null
    }

    /** 从当前 UI 控件收集并构造一份标准化设置快照。 */
    private fun currentState(): LinkGraphSettingsState {
        return LinkGraphSettingsState(
            llmEnabled = llmEnabledCheckBox?.isSelected ?: false,
            provider = (providerComboBox?.selectedItem as? LlmProviderPreset ?: LlmProviderPresets.MOCK).id,
            endpoint = endpointField?.text.orEmpty(),
            apiKey = apiKeyField?.password?.concatToString().orEmpty(),
            model = modelField?.text.orEmpty(),
            timeoutSeconds = (timeoutSpinner?.value as? Number)?.toInt() ?: LinkGraphSettingsState.DEFAULT_TIMEOUT_SECONDS,
            temperature = (temperatureSpinner?.value as? Number)?.toDouble() ?: LinkGraphSettingsState.DEFAULT_TEMPERATURE,
        ).sanitized()
    }

    /**
     * Mock provider 不需要远程连接信息；只有启用 LLM 且选择远程 provider 时才开放 endpoint/apiKey/model 等字段。
     */
    private fun refreshFieldEnabledStates() {
        /** 当前是否勾选启用 LLM。 */
        val llmEnabled = llmEnabledCheckBox?.isSelected == true
        /** 当前选中的 provider 是否为远程供应商。 */
        val remoteProvider = (providerComboBox?.selectedItem as? LlmProviderPreset)?.isRemote == true
        /** 只有远程 LLM 场景才需要开放远程连接相关字段。 */
        val remoteFieldsEnabled = llmEnabled && remoteProvider
        providerComboBox?.isEnabled = llmEnabled
        endpointField?.isEnabled = remoteFieldsEnabled
        apiKeyField?.isEnabled = remoteFieldsEnabled
        modelField?.isEnabled = remoteFieldsEnabled
        timeoutSpinner?.isEnabled = llmEnabled
        temperatureSpinner?.isEnabled = llmEnabled
        validateButton?.isEnabled = llmEnabled
    }

    /** 创建统一样式的帮助提示标签。 */
    private fun createHintLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = BorderFactory.createEmptyBorder(0, 12, 6, 0)
        }
    }

    /** 在带进度条的同步任务中执行设置校验。 */
    private fun runValidation(state: LinkGraphSettingsState): RemoteLlmSettingsValidationResult {
        /** 默认展示的校验结果。 */
        var result = RemoteLlmSettingsValidationResult(
            ok = false,
            message = LinkGraphBundle.message("settings.link-graph.validate.idle"),
        )
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = validator.validate(state)
            },
            LinkGraphBundle.message("settings.link-graph.validate.progress"),
            true,
            null,
        )
        return result
    }

    /** 把校验结果渲染到状态标签上。 */
    private fun updateValidationStatus(result: RemoteLlmSettingsValidationResult) {
        validationStatusLabel?.text = toHtml(result.message)
        validationStatusLabel?.toolTipText = result.message
        validationStatusLabel?.foreground = if (result.ok) {
            JBColor(0x2E7D32, 0x73D37C)
        } else {
            JBColor(0xC62828, 0xFF6B6B)
        }
    }

    /** 把普通文本转换成可直接显示在 Swing 标签中的 HTML。 */
    private fun toHtml(text: String): String {
        return buildString {
            append("<html>")
            text.forEach { ch ->
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '\n' -> append("<br/>")
                    else -> append(ch)
                }
            }
            append("</html>")
        }
    }
}
