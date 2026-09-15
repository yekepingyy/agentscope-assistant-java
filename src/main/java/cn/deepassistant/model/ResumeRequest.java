package cn.deepassistant.model;

import lombok.Data;

/**
 * 文件写入审批续跑请求。{@code userId} 必须和当初 chat 请求一致，
 * 否则找不到待审批记录。
 */
@Data
public class ResumeRequest {
    private String userId;
    private String sessionId;
    private boolean approved;
}
