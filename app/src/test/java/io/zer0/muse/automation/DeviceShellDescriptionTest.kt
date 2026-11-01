package io.zer0.muse.automation

import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.automation.tools.deviceShellDescription
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * device_shell 的描述必须和真实白名单一致。
 *
 * 为什么值得一条测试：描述里写着一个命令、[DeviceCommandPolicy] 却拒绝它，
 * 模型会一直重试同一个注定被拒的命令 —— 比不写描述更糟。反过来（策略放行但没写）
 * 只是少发现一个能力，可以接受。
 */
class DeviceShellDescriptionTest {

    @Test
    fun `description covers every allowed verb`() {
        val description = deviceShellDescription()
        val missing = DeviceCommandPolicy.allowedVerbs.filterNot { description.contains(it) }
        assertTrue("描述漏了这些动词：$missing", missing.isEmpty())
    }

    @Test
    fun `description states the no-pipe boundary`() {
        val description = deviceShellDescription()
        assertTrue("必须写明不支持管道/拼接", description.contains("不支持管道"))
    }

    @Test
    fun `description points read-only work at execute_shell`() {
        assertTrue(deviceShellDescription().contains("execute_shell"))
    }
}
