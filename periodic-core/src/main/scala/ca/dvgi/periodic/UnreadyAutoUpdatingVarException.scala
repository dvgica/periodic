package ca.dvgi.periodic

case object UnreadyAutoUpdatingVarException
    extends IllegalStateException("The AutoUpdatingVar does not yet have a value ready")
