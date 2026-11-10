package io.github.leehj825.myapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import io.github.leehj825.myapp.ai.AiStatus
import io.github.leehj825.myapp.ai.AiSupport

/** The home screen: a list of the tools in this app, and whether on-device AI is ready on this phone. */
class HomeActivity : Activity() {

    private lateinit var aiStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        aiStatus = findViewById(R.id.ai_status)

        val list: LinearLayout = findViewById(R.id.tools)
        for (tool in Tools.all) {
            val item = layoutInflater.inflate(R.layout.item_tool, list, false)
            item.findViewById<TextView>(R.id.tool_name).setText(tool.name)
            item.findViewById<TextView>(R.id.tool_description).setText(tool.description)
            item.setOnClickListener { startActivity(Intent(this, tool.activity)) }
            list.addView(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // Check the on-device AI each time the home screen is shown, and show the result.
        aiStatus.setText(R.string.ai_status_checking)
        AiSupport.refresh(this) { status -> showAiStatus(status) }
    }

    private fun showAiStatus(status: AiStatus) {
        aiStatus.setText(
            when (status) {
                AiStatus.READY -> R.string.ai_status_ready
                AiStatus.DOWNLOADABLE -> R.string.ai_status_downloadable
                AiStatus.DOWNLOADING -> R.string.ai_status_downloading
                AiStatus.UNAVAILABLE -> R.string.ai_status_unavailable
            }
        )
    }
}
