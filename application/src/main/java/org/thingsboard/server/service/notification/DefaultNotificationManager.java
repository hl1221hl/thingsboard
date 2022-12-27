/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.notification;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.DonAsynchron;
import org.thingsboard.rule.engine.api.NotificationManager;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.NotificationId;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.NotificationTargetId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.notification.AlreadySentException;
import org.thingsboard.server.common.data.notification.Notification;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestConfig;
import org.thingsboard.server.common.data.notification.NotificationRequestStatus;
import org.thingsboard.server.common.data.notification.NotificationStatus;
import org.thingsboard.server.common.data.notification.settings.NotificationSettings;
import org.thingsboard.server.common.data.notification.template.DeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TbCallback;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.notification.NotificationRequestService;
import org.thingsboard.server.dao.notification.NotificationService;
import org.thingsboard.server.dao.notification.NotificationSettingsService;
import org.thingsboard.server.dao.notification.NotificationTargetService;
import org.thingsboard.server.dao.notification.NotificationTemplateService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.queue.common.TbProtoQueueMsg;
import org.thingsboard.server.queue.discovery.NotificationsTopicService;
import org.thingsboard.server.queue.provider.TbQueueProducerProvider;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.notification.channels.NotificationChannel;
import org.thingsboard.server.service.subscription.TbSubscriptionUtils;
import org.thingsboard.server.service.telemetry.AbstractSubscriptionService;
import org.thingsboard.server.service.ws.notification.sub.NotificationRequestUpdate;
import org.thingsboard.server.service.ws.notification.sub.NotificationUpdate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("UnstableApiUsage")
public class DefaultNotificationManager extends AbstractSubscriptionService implements NotificationManager, NotificationChannel {

    private final NotificationTargetService notificationTargetService;
    private final NotificationRequestService notificationRequestService;
    private final NotificationService notificationService;
    private final NotificationTemplateService notificationTemplateService;
    private final NotificationSettingsService notificationSettingsService;
    private final DbCallbackExecutorService dbCallbackExecutorService;
    private final NotificationsTopicService notificationsTopicService;
    private final TbQueueProducerProvider producerProvider;
    private Map<NotificationDeliveryMethod, NotificationChannel> channels;


    @Override
    public NotificationRequest processNotificationRequest(TenantId tenantId, NotificationRequest notificationRequest) {
        log.debug("Processing notification request (tenant id: {}, notification targets: {})", tenantId, notificationRequest.getTargets());
        notificationRequest.setTenantId(tenantId);
        NotificationSettings settings = notificationSettingsService.findNotificationSettings(tenantId);
        notificationRequest.getDeliveryMethods().forEach(deliveryMethod -> {
            if (!settings.getDeliveryMethodsConfigs().containsKey(deliveryMethod) || !settings.getDeliveryMethodsConfigs().get(deliveryMethod).isEnabled()) {
                throw new IllegalArgumentException("Delivery method " + deliveryMethod + " is not enabled or configured");
            }
        });

        if (notificationRequest.getAdditionalConfig() != null) {
            NotificationRequestConfig config = notificationRequest.getAdditionalConfig();
            if (config.getSendingDelayInSec() > 0 && notificationRequest.getId() == null) {
                notificationRequest.setStatus(NotificationRequestStatus.SCHEDULED);
                NotificationRequest savedNotificationRequest = notificationRequestService.saveNotificationRequest(tenantId, notificationRequest);
                forwardToNotificationSchedulerService(tenantId, savedNotificationRequest.getId());
                return savedNotificationRequest;
            }
        }

        notificationRequest.setStatus(NotificationRequestStatus.PROCESSED);
        NotificationRequest savedNotificationRequest = notificationRequestService.saveNotificationRequest(tenantId, notificationRequest);

        NotificationProcessingContext ctx = NotificationProcessingContext.builder()
                .tenantId(tenantId)
                .request(savedNotificationRequest)
                .settings(settings)
                .build();
        ctx.init(notificationTemplateService);

        for (NotificationTargetId targetId : notificationRequest.getTargets()) {
            DaoUtil.processBatches(pageLink -> {
                return notificationTargetService.findRecipientsForNotificationTarget(tenantId, ctx.getOriginatorCustomerId(), targetId, pageLink);
            }, 200, recipientsBatch -> {
                List<ListenableFuture<Void>> results = new ArrayList<>();
                for (NotificationDeliveryMethod deliveryMethod : savedNotificationRequest.getDeliveryMethods()) {
                    NotificationChannel notificationChannel = channels.get(deliveryMethod);
                    log.debug("Sending {} notifications for request {} to recipients batch", deliveryMethod, savedNotificationRequest.getId());

                    List<User> recipients = recipientsBatch.getData();
                    for (User recipient : recipients) {
                        ListenableFuture<Void> resultFuture = processForRecipient(notificationChannel, recipient, ctx);
                        DonAsynchron.withCallback(resultFuture, result -> {
                            ctx.getStats().reportSent(deliveryMethod, recipient);
                        }, error -> {
                            ctx.getStats().reportError(deliveryMethod, recipient, error);
                        });
                        results.add(resultFuture);
                    }
                }

                Futures.allAsList(results).addListener(() -> {
                    try {
                        notificationRequestService.updateNotificationRequestStats(tenantId, savedNotificationRequest.getId(), ctx.getStats());
                    } catch (Exception e) {
                        log.error("Failed to update stats for notification request {}", savedNotificationRequest.getId(), e);
                    }
                }, dbCallbackExecutorService);
            });
        }

        return savedNotificationRequest;
    }

    private ListenableFuture<Void> processForRecipient(NotificationChannel notificationChannel, User recipient, NotificationProcessingContext ctx) {
        NotificationDeliveryMethod deliveryMethod = notificationChannel.getDeliveryMethod();
        if (ctx.getStats().contains(deliveryMethod, recipient.getId())) {
            return Futures.immediateFailedFuture(new AlreadySentException());
        }
        String text;
        try {
            DeliveryMethodNotificationTemplate template = ctx.getTemplate(deliveryMethod);
            text = TbNodeUtils.processTemplate(template.getBody(), ctx.createTemplateContext(recipient));
        } catch (Exception e) {
            return Futures.immediateFailedFuture(e);
        }
        return notificationChannel.sendNotification(recipient, text, ctx);
    }

    private void forwardToNotificationSchedulerService(TenantId tenantId, NotificationRequestId notificationRequestId) {
        TransportProtos.NotificationSchedulerServiceMsg.Builder msg = TransportProtos.NotificationSchedulerServiceMsg.newBuilder()
                .setTenantIdMSB(tenantId.getId().getMostSignificantBits())
                .setTenantIdLSB(tenantId.getId().getLeastSignificantBits())
                .setRequestIdMSB(notificationRequestId.getId().getMostSignificantBits())
                .setRequestIdLSB(notificationRequestId.getId().getLeastSignificantBits())
                .setTs(System.currentTimeMillis());
        TransportProtos.ToCoreMsg toCoreMsg = TransportProtos.ToCoreMsg.newBuilder()
                .setNotificationSchedulerServiceMsg(msg)
                .build();
        clusterService.pushMsgToCore(tenantId, notificationRequestId, toCoreMsg, null);
    }

    @Override
    public ListenableFuture<Void> sendNotification(User recipient, String text, NotificationProcessingContext ctx) {
        NotificationRequest request = ctx.getRequest();
        log.trace("Creating notification for recipient {} (notification request id: {})", recipient.getId(), request.getId());
        Notification notification = Notification.builder()
                .requestId(request.getId())
                .recipientId(recipient.getId())
                .type(ctx.getNotificationTemplate().getNotificationType())
                .text(text)
                .info(request.getInfo())
                .originatorType(request.getOriginatorType())
                .status(NotificationStatus.SENT)
                .build();
        try {
            notification = notificationService.saveNotification(recipient.getTenantId(), notification);
        } catch (Exception e) {
            log.error("Failed to create notification for recipient {}", recipient.getId(), e);
            return Futures.immediateFailedFuture(e);
        }
        return onNotificationUpdate(recipient.getTenantId(), recipient.getId(), notification, true);
    }

    @Override
    public void markNotificationAsRead(TenantId tenantId, UserId recipientId, NotificationId notificationId) {
        boolean updated = notificationService.markNotificationAsRead(tenantId, recipientId, notificationId);
        if (updated) {
            log.debug("Marking notification {} as read (recipient id: {}, tenant id: {})", notificationId, recipientId, tenantId);
            Notification notification = notificationService.findNotificationById(tenantId, notificationId);
            onNotificationUpdate(tenantId, recipientId, notification, false);
        }
    }

    @Override
    public void deleteNotificationRequest(TenantId tenantId, NotificationRequestId notificationRequestId) {
        log.debug("Deleting notification request {}", notificationRequestId);
        notificationRequestService.deleteNotificationRequestById(tenantId, notificationRequestId);
        onNotificationRequestUpdate(tenantId, NotificationRequestUpdate.builder()
                .notificationRequestId(notificationRequestId)
                .deleted(true)
                .build());
        clusterService.broadcastEntityStateChangeEvent(tenantId, notificationRequestId, ComponentLifecycleEvent.DELETED);
    }

    @Override
    public NotificationRequest updateNotificationRequest(TenantId tenantId, NotificationRequest notificationRequest) {
        log.debug("Updating notification request {}", notificationRequest.getId());
        notificationRequest = notificationRequestService.saveNotificationRequest(tenantId, notificationRequest);
        onNotificationRequestUpdate(tenantId, NotificationRequestUpdate.builder()
                .notificationRequestId(notificationRequest.getId())
                .notificationInfo(notificationRequest.getInfo())
                .deleted(false)
                .build());
        return notificationRequest;
    }

    private ListenableFuture<Void> onNotificationUpdate(TenantId tenantId, UserId recipientId, Notification notification, boolean isNew) {
        NotificationUpdate update = NotificationUpdate.builder()
                .notification(notification)
                .isNew(isNew)
                .build();
        log.trace("Submitting notification update for recipient {}: {}", recipientId, update);
        return Futures.submit(() -> {
            forwardToSubscriptionManagerService(tenantId, recipientId, subscriptionManagerService -> {
                subscriptionManagerService.onNotificationUpdate(tenantId, recipientId, update, TbCallback.EMPTY);
            }, () -> TbSubscriptionUtils.notificationUpdateToProto(tenantId, recipientId, update));
        }, wsCallBackExecutor);
    }

    private void onNotificationRequestUpdate(TenantId tenantId, NotificationRequestUpdate update) {
        // todo: check delivery method
        log.trace("Submitting notification request update: {}", update);
        wsCallBackExecutor.submit(() -> {
            TransportProtos.ToCoreNotificationMsg notificationRequestUpdateProto = TbSubscriptionUtils.notificationRequestUpdateToProto(tenantId, update);
            Set<String> coreServices = new HashSet<>(partitionService.getAllServiceIds(ServiceType.TB_CORE));
            for (String serviceId : coreServices) {
                TopicPartitionInfo tpi = notificationsTopicService.getNotificationsTopic(ServiceType.TB_CORE, serviceId);
                producerProvider.getTbCoreNotificationsMsgProducer().send(tpi, new TbProtoQueueMsg<>(UUID.randomUUID(), notificationRequestUpdateProto), null);
            }
        });
    }

    @Override
    public NotificationDeliveryMethod getDeliveryMethod() {
        return NotificationDeliveryMethod.WEBSOCKET;
    }

    @Override
    protected String getExecutorPrefix() {
        return "notification";
    }

    @Autowired
    public void setChannels(List<NotificationChannel> channels, NotificationManager websocketNotificationChannel) {
        this.channels = channels.stream().collect(Collectors.toMap(NotificationChannel::getDeliveryMethod, c -> c));
        this.channels.put(NotificationDeliveryMethod.WEBSOCKET, (NotificationChannel) websocketNotificationChannel);
    }

}
