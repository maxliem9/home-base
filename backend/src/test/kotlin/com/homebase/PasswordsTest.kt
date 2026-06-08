package com.homebase

import com.homebase.security.Passwords
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordsTest {

    @Test
    fun `hash is a bcrypt string, not the plaintext`() {
        val hash = Passwords.hash("correct horse battery staple")
        assertTrue(hash.startsWith("\$2"), "expected a bcrypt 2x hash, got: $hash")
        assertFalse(hash.contains("correct horse battery staple"))
    }

    @Test
    fun `equal passwords get different hashes (per-password salt)`() {
        assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))
    }

    @Test
    fun `verify accepts the correct password`() {
        val hash = Passwords.hash("s3cret")
        assertTrue(Passwords.verify("s3cret", hash))
    }

    @Test
    fun `verify rejects a wrong password`() {
        val hash = Passwords.hash("s3cret")
        assertFalse(Passwords.verify("guess", hash))
    }

    @Test
    fun `verify rejects a non-bcrypt stored hash without throwing`() {
        // Legacy SHA-256 hex (and any other non-bcrypt string) must fail closed, so the
        // first post-upgrade login/seed re-hashes instead of crashing.
        val legacySha256 = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"
        assertFalse(Passwords.verify("password", legacySha256))
    }

    @Test
    fun `verifyDummy always fails closed regardless of input`() {
        // The unknown-user login path calls this only for its bcrypt timing cost; it must
        // never authenticate. No input may make it return true.
        assertFalse(Passwords.verifyDummy("anything"))
        assertFalse(Passwords.verifyDummy(""))
        assertFalse(Passwords.verifyDummy("dummy-password-no-account-matches-this"))
    }

    @Test
    fun `verify handles passwords longer than bcrypt's 72-byte limit`() {
        val long = "x".repeat(200)
        val hash = Passwords.hash(long)
        assertTrue(Passwords.verify(long, hash))
        // Two long passwords sharing the first 72 bytes must not collide.
        assertFalse(Passwords.verify("x".repeat(199) + "y", hash))
    }
}
