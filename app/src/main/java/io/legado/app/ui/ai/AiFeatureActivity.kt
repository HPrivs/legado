package io.legado.app.ui.ai

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiFeatureBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiFeatureActivity : BaseActivity<ActivityAiFeatureBinding>() {

    override val binding by viewBinding(ActivityAiFeatureBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_container, AiConfigFragment())
                .commit()
        }
    }
}
