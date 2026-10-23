package com.mockverse.monolith.controller;

import com.mockverse.monolith.dto.AnswerResponse;
import com.mockverse.monolith.dto.MessageView;
import com.mockverse.monolith.dto.StartResponse;
import com.mockverse.monolith.service.InterviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 面试接口。D13 会在这里加 SSE 流式版本。 */
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    public record AnswerRequest(String answer) {}

    @PostMapping("/start")
    public StartResponse start(@RequestParam(defaultValue = "1") Long userId,
                               @RequestParam(required = false) Long questionId,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) String difficulty) {
        return interviewService.start(userId, questionId, category, difficulty);
    }

    @PostMapping("/{sessionId}/answer")
    public AnswerResponse answer(@PathVariable Long sessionId, @RequestBody AnswerRequest request) {
        return interviewService.answer(sessionId, request.answer());
    }

    @GetMapping("/{sessionId}/history")
    public List<MessageView> history(@PathVariable Long sessionId) {
        return interviewService.history(sessionId);
    }
}
