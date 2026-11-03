package app.lumen.core.system

import android.service.notification.NotificationListenerService

/**
 * Exists only because Android shows what's playing to apps with notification access. Nothing here
 * looks at notifications; once it's turned on, [DeviceStatus] reads the media sessions.
 */
class MediaAccessService : NotificationListenerService()
