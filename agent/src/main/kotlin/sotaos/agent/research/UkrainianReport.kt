package sotaos.agent.research

import kotlinx.serialization.json.JsonObject

internal object UkrainianReport {
    fun render(records: List<JsonObject>): String = buildString {
        append("# Дослідницький звіт SOTA\n\n")
        append("Статус: кандидати знань, не канонічні знання. A21: чернетка.\n")
        append("Зовнішні тексти є недовіреними даними. Висновки потребують людської перевірки.\n\n")
        append("## Джерела\n\n")
        records.filter { it.field("operation") == "SOURCE" }.forEach { source ->
            val data = source.data()
            append("Джерело: ${source.field("id")}\n")
            append("URL: ${safe(data.field("url"))}\n")
            append("Автор: ${safe(data.field("author"))}\n")
            append("Опубліковано: ${safe(data.field("publishedAt"))}\n")
            append("Отримано: ${source.field("recordedAt")}\n")
            append("SHA-256 тексту: ${data.field("contentHash")}\n")
            append("Назва: ${safe(data.field("title"))}\n")
            append("Текст джерела (недовірений):\n${indent(data.field("text"))}\n\n")
        }
        append("## Аргументи та контраргументи\n\n")
        records.filter { it.field("operation") == "ARGUMENT" }.forEach { argument ->
            val data = argument.data()
            val label = if (data.field("kind") == "ARGUMENT") "Аргумент" else "Контраргумент"
            append("$label: ${argument.field("id")}\n")
            append("Джерело: ${data.field("sourceId")}; заперечує: ${data.field("counters")}\n")
            append("Текст (недовірений):\n${indent(data.field("text"))}\n")
            append("Дослівна цитата:\n${indent(data.field("quote"))}\n\n")
        }
        append("## Чернетки публікацій\n\n")
        records.filter { it.field("operation") == "DRAFT" }.forEach { draft ->
            val id = draft.field("id")
            val approved = records.any {
                it.field("operation") == "APPROVAL" && it.data().field("draftId") == id
            }
            append("Чернетка: $id; перевірено людиною: ${if (approved) "так" else "ні"}\n")
            append("SHA-256: ${draft.data().field("contentHash")}\n")
            append("Публікація вимкнена.\n${indent(draft.data().field("text"))}\n\n")
        }
        append("## Межі дослідження\n\n")
        append("Істинність джерел і повнота аргументів не підтверджені автоматично.\n")
        append("A01–A20 залишаються чинними; A21 не надає виконавчих повноважень.\n")
    }

    private fun safe(value: String): String = value.map {
        if (it.isISOControl() || it in "<>[]`\\") ' ' else it
    }.joinToString("")

    private fun indent(value: String): String = value.lines().joinToString("\n") { "    ${safe(it)}" }
}
