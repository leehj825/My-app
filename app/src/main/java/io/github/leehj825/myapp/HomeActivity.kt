package io.github.leehj825.myapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** The home screen: a list of the tools in this app. */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val list: LinearLayout = findViewById(R.id.tools)
        for (tool in Tools.all) {
            val item = layoutInflater.inflate(R.layout.item_tool, list, false)
            item.findViewById<TextView>(R.id.tool_name).setText(tool.name)
            item.findViewById<TextView>(R.id.tool_description).setText(tool.description)
            item.setOnClickListener { startActivity(Intent(this, tool.activity)) }
            list.addView(item)
        }
    }
}
