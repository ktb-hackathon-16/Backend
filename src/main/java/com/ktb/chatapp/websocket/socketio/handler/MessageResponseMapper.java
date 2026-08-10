package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.service.FileUrl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메시지를 응답 DTO로 변환하는 매퍼
 * 파일 정보, 사용자 정보 등을 포함한 MessageResponse 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    private final FileRepository fileRepository;

    /**
     * Message 엔티티를 MessageResponse DTO로 변환
     *
     * @param message 변환할 메시지 엔티티
     * @param sender 메시지 발신자 정보 (null 가능)
     * @return MessageResponse DTO
     */
    public MessageResponse mapToMessageResponse(Message message, User sender) {
        File file = Optional.ofNullable(message.getFileId())
                .flatMap(fileRepository::findById)
                .orElse(null);
        return mapToMessageResponse(message, sender, file);
    }

    /**
     * 여러 메시지 응답을 만들 때 발신자·첨부파일을 한 번씩만 조회한다.
     */
    public List<MessageResponse> mapToMessageResponses(
            List<Message> messages,
            Map<String, User> usersById) {
        Set<String> fileIds = messages.stream()
                .map(Message::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, File> filesById = fileIds.isEmpty()
                ? Map.of()
                : fileRepository.findAllById(fileIds).stream()
                        .filter(file -> file.getId() != null)
                        .collect(Collectors.toMap(File::getId, file -> file, (first, ignored) -> first));

        return messages.stream()
                .map(message -> {
                    String fileId = message.getFileId();
                    String senderId = message.getSenderId();
                    return mapToMessageResponse(
                            message,
                            senderId == null ? null : usersById.get(senderId),
                            fileId == null ? null : filesById.get(fileId));
                })
                .toList();
    }

    MessageResponse mapToMessageResponse(Message message, User sender, File file) {
        MessageResponse.MessageResponseBuilder builder = MessageResponse.builder()
                .id(message.getId())
                .content(message.getContent())
                .type(message.getType())
                .timestamp(message.toTimestampMillis())
                .roomId(message.getRoomId())
                .reactions(message.getReactions() != null ?
                        message.getReactions() : new HashMap<>())
                .readers(message.getReaders() != null ?
                        message.getReaders() : new ArrayList<>());

        // 발신자 정보 설정
        if (sender != null) {
            builder.sender(UserResponse.builder()
                    .id(sender.getId())
                    .name(sender.getName())
                    .email(sender.getEmail())
                    .profileImage(FileUrl.of(sender.getProfileImage()))
                    .build());
        }

        // 파일 정보 설정
        if (file != null) {
            builder.file(FileResponse.builder()
                    .id(file.getId())
                    .filename(file.getFilename())
                    .originalname(file.getOriginalname())
                    .mimetype(file.getMimetype())
                    .size(file.getSize())
                    .build());
        }

        // 메타데이터 설정
        if (message.getMetadata() != null) {
            builder.metadata(message.getMetadata());
        }

        return builder.build();
    }
}
