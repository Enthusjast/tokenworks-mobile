package httpapi

import "tokenworks-mobile/services/remote/internal/relay"

func (handler *Handler) ConfigureSendQueueLimits(limits relay.SendQueueLimits) error {
	return handler.realtime.ConfigureSendQueueLimits(limits)
}
