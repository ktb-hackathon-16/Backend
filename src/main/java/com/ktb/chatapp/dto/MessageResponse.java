package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.AiType;
import com.ktb.chatapp.model.MessageType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 메시지 응답 DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {
    @JsonProperty("_id")
    private String id;
    
    @JsonProperty("room")
    private String roomId;
    
    private String content;
    
    private UserResponse sender;
    
    private MessageType type;
    
    @JsonProperty("file")
    private FileResponse file;
    
    private AiType aiType;
    
    private long timestamp;
    
    private Map<String, Set<String>> reactions;

    // [REMOVED] dto/MessageResponse.java: List<Message.MessageReader> readers 필드 삭제.
    // Last Read Watermark 방식으로 전환하면서 메시지마다 readers 배열을 실어보내지 않는다.
    // 안읽음 표시는 프론트가 room.readReceipts(워터마크 맵)와 메시지 timestamp를 비교해 계산한다.

    // metadata는 자유 형식 (Map<String, Object>)
    private Map<String, Object> metadata;
}
