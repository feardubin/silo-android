package org.siloserver.silo.model.auth

internal actual fun isPasswordUppercase(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.UPPERCASE_LETTER.toInt()

internal actual fun isPasswordSpecial(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.OTHER_SYMBOL.toInt() -> true
    else -> false
}
