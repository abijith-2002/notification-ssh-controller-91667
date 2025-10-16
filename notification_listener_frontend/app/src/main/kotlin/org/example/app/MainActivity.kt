package org.example.app

import org.example.list.LinkedList
import org.example.utilities.SplitUtils
import org.example.utilities.StringUtils

import android.widget.TextView
import android.os.Bundle
import android.app.Activity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.textView)
        textView.text = buildMessage()
    }

    private fun buildMessage(): String {
        val tokens: LinkedList = SplitUtils.split(MessageUtils.message())
        val result: String = StringUtils.join(tokens)
        // Simple capitalization: capitalize each word's first letter using Kotlin
        return result
            .lowercase()
            .split(" ")
            .joinToString(" ") { s ->
                if (s.isNotEmpty()) s.replaceFirstChar { it.titlecase() } else s
            }
    }
}
