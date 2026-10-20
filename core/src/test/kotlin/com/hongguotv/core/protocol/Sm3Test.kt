package com.hongguotv.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Sm3Test {
    private fun assertSm3(input: String, expected: String) {
        assertEquals(expected, sm3(input.toByteArray(Charsets.UTF_8)).hex())
    }
    @Test fun standardVectors() {
        // GB/T 32907-2016 附录 A
        assertSm3("abc", "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0")
        assertSm3("abcd", "82ec580fe6d36ae4f81cae3c73f4a5b3b5a09c943172dc9053c69fd8e18dca1e")
    }
    @Test fun paddingBoundaries() {
        // 空串、填充临界（55/56/63/64/65 字节走不同填充与分块路径）、跨多块长输入
        assertSm3("", "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b")
        assertSm3("a".repeat(55), "288337eef51eec62e7544d7270424c8dbe656254c99852870a73b2453a6a7fb1")
        assertSm3("a".repeat(63), "587308543551881ebd70d27ad358ff5dcdf24ac54822e2f7b7c3edce0985d21b")
        assertSm3("a".repeat(64), "616ec433c359e7c2b19f360e2b8f2a1b6e9ed76b8dc1a7d207b31a5341c611e9")
        assertSm3("a".repeat(65), "3d1d94afa238ec3e2bbc20ad504702b24c16f2889c94973f2f8da3526c44e4bc")
        assertSm3("a".repeat(200), "a8da99c801bbd65ec00992ed5cbef851f275e98da9fb2d4f5be184f81473d5dd")
    }
}
