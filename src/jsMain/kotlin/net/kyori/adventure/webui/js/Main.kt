package net.kyori.adventure.webui.js

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.dom.hasClass
import kotlinx.html.b
import kotlinx.html.i
import kotlinx.html.p
import kotlinx.serialization.encodeToString
import net.kyori.adventure.webui.COMPONENT_CLASS
import net.kyori.adventure.webui.DATA_CLICK_EVENT_ACTION
import net.kyori.adventure.webui.DATA_CLICK_EVENT_VALUE
import net.kyori.adventure.webui.DATA_INSERTION
import net.kyori.adventure.webui.PARAM_EDITOR_TOKEN
import net.kyori.adventure.webui.Serializers
import net.kyori.adventure.webui.URL_API
import net.kyori.adventure.webui.URL_EDITOR
import net.kyori.adventure.webui.URL_EDITOR_INPUT
import net.kyori.adventure.webui.URL_EDITOR_OUTPUT
import net.kyori.adventure.webui.URL_IN_GAME_PREVIEW
import net.kyori.adventure.webui.URL_MINI_TO_HTML
import net.kyori.adventure.webui.URL_MINI_TO_JSON
import net.kyori.adventure.webui.URL_MINI_TO_TREE
import net.kyori.adventure.webui.editor.EditorInput
import net.kyori.adventure.webui.tryDecodeFromString
import net.kyori.adventure.webui.websocket.Call
import net.kyori.adventure.webui.websocket.Combined
import net.kyori.adventure.webui.websocket.InGamePreview
import net.kyori.adventure.webui.websocket.Packet
import net.kyori.adventure.webui.websocket.Placeholders
import net.kyori.adventure.webui.websocket.Response
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLPreElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.Node
import org.w3c.dom.WebSocket
import org.w3c.dom.Window
import org.w3c.dom.asList
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.EventTarget
import org.w3c.dom.get
import org.w3c.dom.set
import org.w3c.dom.url.URLSearchParams
import org.w3c.fetch.Headers
import org.w3c.fetch.NO_CACHE
import org.w3c.fetch.RequestCache
import org.w3c.fetch.RequestInit
import kotlin.js.Promise
import kotlin.js.json

private val homeUrl: String by lazy { window.location.href.split('?')[0] }
private val urlParams: URLSearchParams by lazy { URLSearchParams(window.location.search) }
private val modeButtons: List<HTMLAnchorElement> by lazy { document.getElementsByClassName("mc-mode").asList().unsafeCast<List<HTMLAnchorElement>>() }

public const val PARAM_INPUT: String = "input"
public const val PARAM_MODE: String = "mode"
public const val PARAM_BACKGROUND: String = "bg"
public const val PARAM_STRING_PLACEHOLDERS: String = "st"
public const val PARAM_SHORT_LINK: String = "x"

private var isInEditorMode: Boolean = false
private lateinit var editorInput: EditorInput

public lateinit var currentMode: Mode
private lateinit var webSocket: WebSocket

public fun mainLoaded() {
    // Defaults for all toast messages
    bulmaToast.setDefaults(
        json(
            "position" to "bottom-right",
            "dismissible" to true,
            "pauseOnHover" to true,
            "duration" to 6000,
            "animate" to json("in" to "fadeIn", "out" to "fadeOut")
        )
    )

    // EDITOR
    val input = document.element<HTMLTextAreaElement>("input")
    val saveButton = document.element<HTMLInputElement>("editor-save")
    urlParams.get(PARAM_EDITOR_TOKEN)?.let { token ->
        isInEditorMode = true

        window.fetch(
            "$URL_API$URL_EDITOR$URL_EDITOR_INPUT?$PARAM_EDITOR_TOKEN=$token",
            RequestInit("GET")
        ).then { response ->
            if (!response.ok) {
                isInEditorMode = false
                bulmaToast.toast(
                    "Could not load editor session!",
                    type = "is-error"
                )
            } else {
                response.text().then { text ->
                    val possibleEditorInput =
                        Serializers.json.tryDecodeFromString<EditorInput>(text)
                    if (possibleEditorInput == null) {
                        isInEditorMode = false
                        bulmaToast.toast(
                            "Could not load editor session!",
                            type = "is-error"
                        )
                    } else {
                        isInEditorMode = true
                        editorInput = possibleEditorInput
                        input.value = editorInput.input
                        bulmaToast.toast(
                            "Loaded editor session! Press the save icon to generate a command to save the message to ${editorInput.application}."
                        )
                        saveButton.classList.remove("is-hidden")
                    }
                }
            }
        }
    }
    saveButton.addEventListener(
        "click",
        {
            if (isInEditorMode && ::editorInput.isInitialized) {
                window.fetch(
                    "$URL_API$URL_EDITOR$URL_EDITOR_OUTPUT",
                    RequestInit("POST", body = input.value)
                )
                    .then { response -> response.text() }
                    .then { token ->
                        window.navigator.clipboard.writeText(editorInput.command.replace("{token}", token))
                    }
                    .then { bulmaToast.toast("The command to run in-game has been copied to your clipboard!") }
            }
        }
    )

    // WEBSOCKET
    startWebsocket()

    // CORRECT HOME LINK
    document.element<HTMLAnchorElement>("home-link").href = homeUrl

    // OBFUSCATION
    window.setInterval({ obfuscateAll() }, 10)

    // OUTPUT BOXES
    val outputPre = document.element<HTMLPreElement>("output-pre")
    val outputPane = document.element<HTMLDivElement>("output-pane")

    // CARET
    val chatBox = document.element<HTMLDivElement>("chat-entry-box")
    window.setInterval({ chatBox.innerHTML = if (chatBox.innerHTML == "_") " " else "_" }, 380)

    // BUTTONS
    val settingsBox = document.getElementById("settings-box")
    document.getElementsByClassName("settings-button").asList().forEach { element ->
        element.addEventListener("click", { settingsBox!!.classList.toggle("is-active") })
    }
    val placeholdersBox = document.getElementById("placeholders-box")
    document.getElementsByClassName("placeholders-button").asList().forEach { element ->
        element.addEventListener(
            "click",
            {
                // classList.toggle returns whether the class is in the classlist after the operation
                // Since we care about updating everything after the uses closes the modal, we must negate the result.
                val opened = placeholdersBox!!.classList.toggle("is-active")
                if (!opened) {
                    val newPlaceholders = readPlaceholders()
                    window.localStorage[PARAM_STRING_PLACEHOLDERS] = Serializers.json.encodeToString(
                        newPlaceholders.stringPlaceholders
                    )
                    webSocket.send(newPlaceholders)
                }
            }
        )
    }
    document.getElementsByClassName("add-placeholder-button").asList().forEach { element ->
        element.addEventListener("click", { UserPlaceholder.addToList() })
    }

    // MODES
    currentMode = Mode.DEFAULT
    outputPre.classList.add(currentMode.className)
    outputPane.classList.add(currentMode.className)

    val modeButtons = document.getElementsByClassName("mc-mode").asList().unsafeCast<List<HTMLAnchorElement>>()
    modeButtons.forEach { element ->
        // set is-active on the current mode first
        val mode = Mode.valueOf(element.dataset["mode"]!!)
        if (currentMode == mode) {
            element.classList.add("is-active")
        }

        // then add event listeners for the rest
        element.addEventListener(
            "click",
            {
                setMode(mode)
                parse()
            }
        )
    }

    // SETTINGS
    val settingBackground = document.element<HTMLSelectElement>("setting-background")
    currentBackground = settingBackground.value
    settingBackground.addEventListener(
        "change",
        {
            currentBackground = settingBackground.value
        }
    )

    // SHARING
    document.getElementById("full-link-share-button")!!.addEventListener(
        "click",
        {
            val inputValue = encodeURIComponent(input.value)
            val placeholders = readPlaceholders()
            var link =
                "$homeUrl?$PARAM_MODE=${currentMode.paramName}&$PARAM_INPUT=$inputValue"
            if (currentMode != Mode.SERVER_LIST) {
                link += "&$PARAM_BACKGROUND=$currentBackground"
            }
            if (!placeholders.stringPlaceholders.isNullOrEmpty()) {
                link += "&$PARAM_STRING_PLACEHOLDERS="
                link +=
                    encodeURIComponent(
                        Serializers.json.encodeToString(placeholders.stringPlaceholders)
                    )
            }
            window.navigator.clipboard.writeText(link).then {
                bulmaToast.toast("Shareable permanent link copied to clipboard!")
            }
        }
    )
    document.getElementById("short-link-share-button")!!.addEventListener(
        "click",
        {
            bytebinStore(
                Combined(
                    miniMessage = input.value,
                    placeholders = readPlaceholders(),
                    background = if (currentMode != Mode.SERVER_LIST) currentBackground else null,
                    mode = currentMode.paramName
                )
            )
                .then { code -> "$homeUrl?$PARAM_SHORT_LINK=$code" }
                .then { link ->
                    window.navigator.clipboard.writeText(link).then(
                        { bulmaToast.toast("Shareable short link copied to clipboard!") },
                        {
                            // This is run when writing to the clipboard is rejected (by Safari)
                            createCopyModal("Short link generated", link)
                        }
                    )
                }
        }
    )
    // Roll up the share dropdown after making a choice
    /*
    TODO(rymiel): Perhaps the dropdown could stay open when the "short link" option is selected, instead turning
      into a loading wheel, then the dropdown can close once that loading is done.
     */
    document.getElementsByClassName("share-button").asList().forEach { element ->
        element.addEventListener(
            "click",
            {
                element.closest(".dropdown")!!.classList.toggle("is-active")
            }
        )
    }

    // CLIPBOARD
    document.getElementById("copy-button")!!.addEventListener(
        "click",
        {
            window.navigator.clipboard.writeText(input.value.replace("\n", "\\n")).then {
                bulmaToast.toast("Input text copied to clipboard!")
            }
        }
    )
    document.getElementById("export-to-json-button")!!.addEventListener(
        "click",
        {
            window.postPacket(
                "$URL_API$URL_MINI_TO_JSON",
                Combined(miniMessage = input.value, placeholders = readPlaceholders())
            )
                .then { response -> response.text() }
                .then { text -> window.navigator.clipboard.writeText(text) }
                .then { bulmaToast.toast("JSON copied to clipboard!") }
        }
    )

    document.getElementById("show-tree-button")!!.addEventListener(
        "click",
        {
            window.postPacket(
                "$URL_API$URL_MINI_TO_TREE",
                Combined(miniMessage = input.value, placeholders = readPlaceholders())
            )
                .then { response -> response.text() }
                .then { text ->
                    val escaped =
                        text.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                    bulmaToast.toast("<pre>$escaped</pre>")
                }
        }
    )

    var inGamePreviewKey = (1..8).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    document.getElementById("in-game-preview-button")!!.addEventListener(
        "click",
        {
            window.postPacket(
                "$URL_API$URL_IN_GAME_PREVIEW",
                InGamePreview(miniMessage = input.value, key = inGamePreviewKey)
            )
                .then { response -> response.text() }
                .then { text -> window.navigator.clipboard.writeText(text) }
                .then { bulmaToast.toast("Minecraft Server Hostname copied to clipboard!") }
        }
    )

    // EDITOR

    // BURGER MENU
    val burgerMenu = document.getElementById("burger-menu")!!
    val navbarMenu = document.getElementById("navbar-menu")!!
    burgerMenu.addEventListener(
        "click",
        {
            burgerMenu.classList.toggle("is-active")
            navbarMenu.classList.toggle("is-active")
        }
    )

    // EVENTS
    document.addEventListener(
        "click",
        { event ->
            val target = event.target
            if (target is HTMLSpanElement && target.classList.contains(COMPONENT_CLASS)) {
                checkClickEvents(target, EventType.all())

                // we need to prevent propagation as we do that ourselves manually
                event.stopPropagation()
            }
        }
    )

    // DROPDOWNS
    document.getElementsByClassName("dropdown-trigger").asList().forEach { element ->
        element.addEventListener(
            "click",
            {
                if (element.classList.contains("swatch-trigger")) {
                    // This should hopefully make it so any text selected before pressing the color dropdown should stay visually selected
                    val inputBox = document.element<HTMLTextAreaElement>("input")
                    inputBox.focus()
                }
                element.parentElement!!.classList.toggle("is-active")
            }
        )
    }

    installHoverManager()
    installStyleButtons()
}

public fun main() {
    document.addEventListener(
        "DOMContentLoaded",
        {
            mainLoaded()
        }
    )
}

// TODO(rymiel): This could maybe go into the Mode.kt file like how Background.kt has its logic
public fun setMode(newMode: Mode) {
    val outputPre = document.element<HTMLPreElement>("output-pre")
    val outputPane = document.element<HTMLDivElement>("output-pane")

    // remove active
    modeButtons.forEach { button -> button.classList.remove("is-active") }

    // now add it again lmao 10/10 code
    val newModeButton = modeButtons.first { button -> button.dataset["mode"]!! == newMode.toString() }
    newModeButton.classList.add("is-active")
    currentMode = newMode
    // Store current mode for persistence
    window.localStorage[PARAM_MODE] = currentMode.paramName

    // swap the class for the pane
    Mode.MODES.forEach { mode ->
        if (newMode == mode) {
            outputPre.classList.add(mode.className)
            outputPane.classList.add(mode.className)
        } else {
            outputPre.classList.remove(mode.className)
            outputPane.classList.remove(mode.className)
        }
    }

    updateBackground()
}

private fun readPlaceholders(): Placeholders {
    val userPlaceholders = UserPlaceholder.allInList()
    val stringPlaceholders = mutableMapOf<String, String>()
    userPlaceholders.filter { t -> t.key.isNotEmpty() && t.value.isNotEmpty() }.forEach { t ->
        stringPlaceholders[t.key] = t.value
    }
    return Placeholders(stringPlaceholders = stringPlaceholders)
}

private fun startWebsocket() {
    webSocket =  if (window.location.hostname == "localhost" || window.location.hostname == "127.0.0.1") {
        WebSocket("ws://${window.location.host}$URL_API$URL_MINI_TO_HTML")
    } else {
        WebSocket("wss://${window.location.host}$URL_API$URL_MINI_TO_HTML")
    }
    webSocket.onopen = { onWebsocketReady() }
    webSocket.onclose = { startWebsocket() }
    // A closed websocket will be handled by the above, but log the error to console for debugging sake
    webSocket.onerror = { err -> console.log("Websocket error: $err") }
}

private fun onWebsocketReady() {
    // SHARING
    val inputBox = document.element<HTMLTextAreaElement>("input")

    if (!isInEditorMode) {
        val shortCode = urlParams.get(PARAM_SHORT_LINK)
        if (shortCode != null) {
            restoreFromShortLink(shortCode, inputBox, webSocket).then { parse() }
        } else {
            urlParams.getFromParamsOrLocalStorage(PARAM_INPUT)?.also { inputString ->
                inputBox.value = inputString
            }
            var stringPlaceholders: Map<String, String>? = null
            urlParams.getFromParamsOrLocalStorage(PARAM_STRING_PLACEHOLDERS)?.also { inputString ->
                stringPlaceholders = Serializers.json.tryDecodeFromString(inputString)
            }
            stringPlaceholders?.forEach { (k, v) ->
                UserPlaceholder.addToList().apply {
                    key = k
                    value = v
                }
            }
            urlParams.getFromParamsOrLocalStorage(PARAM_BACKGROUND)?.also { background ->
                currentBackground = background
            }
            urlParams.getFromParamsOrLocalStorage(PARAM_MODE)?.also { mode ->
                setMode(Mode.fromString(mode))
            }
            webSocket.send(
                Placeholders(stringPlaceholders = stringPlaceholders)
            )
        }
    }

    parse()

    // INPUT
    val input = document.element<HTMLTextAreaElement>("input")
    input.addEventListener("keyup", { parse() })
    input.addEventListener("change", { parse() })
    input.addEventListener(
        "paste",
        { event ->
            event.preventDefault()
            val paste = event.unsafeCast<ClipboardEvent>().clipboardData!!.getData("text")
            document.execCommand("insertText", false, paste.replace("\\n", "\n"))
        }
    )
    val output = document.getElementById("output-pre")!!
    webSocket.onmessage =
        { messageEvent ->
            val data = messageEvent.data
            if (data is String) {
                val response = Serializers.json.tryDecodeFromString<Response>(data)

                response?.parseResult?.let { result ->
                    if (result.success && result.dom != null) {
                        output.textContent = ""

                        document.createElement("div").also { div ->
                            div.innerHTML = result.dom.replace("\n", "<br>")
                            output.append(div)
                        }

                        // reset scroll to bottom (like how chat works)
                        if (currentMode == Mode.CHAT_OPEN || currentMode == Mode.CHAT_CLOSED) {
                            output.scrollTop = output.scrollHeight.toDouble()
                        }
                    } else if (!result.success && result.errorMessage != null) {
                        console.error("A parse error occurred: ${result.errorMessage}")
                    } else {
                        console.error("An unknown error occurred!")
                    }
                }
            }
        }
}

private fun checkClickEvents(target: EventTarget?, typesToCheck: Collection<EventType>) {
    if (target is HTMLSpanElement && target.classList.contains(COMPONENT_CLASS)) {
        val remainingTypesToCheck = mutableSetOf<EventType>()

        if (EventType.CLICK.isUsable(currentMode) && typesToCheck.contains(EventType.CLICK)) {
            val clickAction = target.dataset[DATA_CLICK_EVENT_ACTION.camel]

            if (clickAction == null) {
                remainingTypesToCheck.add(EventType.CLICK)
            } else {
                val content = target.dataset[DATA_CLICK_EVENT_VALUE.camel] ?: ""
                val actionName = clickAction.replace('_', ' ').replaceFirstChar(Char::uppercase)
                bulmaToast.toast(type = "is-info") {
                    p {
                        b { text("Click Event") }
                    }
                    p {
                        text("Action: ")
                        i { text(actionName) }
                    }
                    p {
                        text("Content: ")
                        i { text(content) }
                    }
                }
            }
        }

        if (EventType.INSERTION.isUsable(currentMode) &&
            typesToCheck.contains(EventType.INSERTION)
        ) {
            val insertion = target.dataset[DATA_INSERTION.camel]

            if (insertion == null) {
                typesToCheck + EventType.INSERTION
            } else {
                bulmaToast.toast(type = "is-info") {
                    p {
                        b { text("Insertion") }
                    }
                    p {
                        text("Content: ")
                        i { text(insertion) }
                    }
                }
            }
        }

        if (remainingTypesToCheck.isNotEmpty()) {
            // recurse up to the parent
            checkClickEvents(target.parentElement, remainingTypesToCheck)
        }
    }
}

private fun obfuscateAll() {
    document.getElementsByClassName("obfuscated").asList().forEach { obfuscate(it) }
}

private fun obfuscate(input: Node) {
    if (input is Element && input.hasClass("hover")) return
    val childNodes = input.childNodes
    if (childNodes.length > 0) {
        childNodes.asList().forEach { obfuscate(it) }
    }
    if (input.nodeType == Node.TEXT_NODE) {
        input.nodeValue = obfuscate(input.nodeValue.orEmpty())
    }
}

private fun CharArray.map(transform: (Char) -> Char): CharArray {
    for (i in this.indices) {
        this[i] = transform(this[i])
    }
    return this
}

private fun obfuscate(input: String): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')

    return input.toCharArray().map { if (it != ' ') allowedChars.random() else it }.concatToString()
}

@OptIn(ExperimentalStdlibApi::class)
private fun parse() {
    // don't do anything if we're not initialised yet
    if (::webSocket.isInitialized) {
        val input = document.element<HTMLTextAreaElement>("input").value
        // Store current input for persistence
        window.localStorage[PARAM_INPUT] = input

        if (input.isEmpty() && currentMode != Mode.SERVER_LIST) {
            // we don't want to parse if input is empty (server list mode is an exception!)
            document.getElementById("output-pre")!!.textContent = ""
        } else {
            val lines =
                input.split("\n", "\\n").let { list ->
                    // some modes can only render a certain amount of lines
                    when (currentMode) {
                        Mode.CHAT_CLOSED -> list.safeSubList(0, 10)
                        Mode.SERVER_LIST ->
                            buildList(2) {
                                add(list.getOrNull(0) ?: "\u200B")
                                add(list.getOrNull(1) ?: "\u200B")
                            }
                        else -> list
                    }
                }

            val combinedLines =
                lines.joinToString(separator = "\n") { line ->
                    // we don't want to lose empty lines, so replace them with zero-width space
                    if (line == "") "\u200B" else line
                }

            webSocket.send(Call(combinedLines, isolateNewlines = currentMode == Mode.LORE))
        }
    }
}

private inline fun <reified T> List<T>.safeSubList(startIndex: Int, endIndex: Int): List<T> =
    if (endIndex > size) this else this.subList(startIndex, endIndex)

public fun WebSocket.send(packet: Packet) {
    this.send(Serializers.json.encodeToString(packet))
}

public inline fun <reified T> Window.postPacket(url: String, packet: T): Promise<org.w3c.fetch.Response> {
    return this.fetch(
        url,
        RequestInit(
            method = "POST",
            cache = RequestCache.NO_CACHE,
            headers = Headers(json("Content-Type" to "text/plain; charset=UTF-8")),
            body = Serializers.json.encodeToString(packet)
        )
    )
}
