package com.example.utils

import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
  fun formatUsd(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(amount)
  }

  fun formatUsd(amountString: String): String {
    val cleaned = amountString.replace(Regex("[^\\d.]"), "")
    val parsed = cleaned.toDoubleOrNull() ?: 0.0
    return formatUsd(parsed)
  }

  fun parseCurrencyInput(input: String): Double {
    val cleaned = input.replace(Regex("[^\\d.]"), "")
    return cleaned.toDoubleOrNull() ?: 0.0
  }
}
