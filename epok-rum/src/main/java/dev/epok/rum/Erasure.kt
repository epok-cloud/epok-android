package dev.epok.rum

/** Deliberately no transport, ingest key, tenant deletion, or generic DELETE API. */
object SessionErasure {
    enum class Result { REQUIRES_SESSION_SCOPED_AUTHORIZATION }
    fun request(): Result = Result.REQUIRES_SESSION_SCOPED_AUTHORIZATION
    // A future implementation must use an authenticated user/backend path with a short-lived
    // capability bound to ONE tenant + session + delete action. The backend checks ownership,
    // expiry and replay protection. A public ingest key is never deletion authorization.
}
