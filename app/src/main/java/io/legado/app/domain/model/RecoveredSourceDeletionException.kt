package io.legado.app.domain.model

import java.io.IOException

class RecoveredSourceDeletionException(cause: Exception) :
    IOException("Could not remove the recovered TXT source", cause)
