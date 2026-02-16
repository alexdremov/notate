package com.alexdremov.notate.export

import org.junit.Test

class OsCheckTest {
    @Test
    fun checkOs() {
        println("OS_NAME: ${System.getProperty("os.name")}")
    }
}
