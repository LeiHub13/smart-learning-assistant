package com.example.learningassistant.web.controller;

import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.note.entity.Note;
import com.example.learningassistant.note.service.NoteService;
import com.example.learningassistant.security.AuthUser;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 学习笔记接口：按课程记录学习/练习心得。
 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping
    public ApiResponse<List<Note>> list(HttpServletRequest request, @RequestParam(required = false) Long courseId) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(noteService.list(u.id(), courseId));
    }

    @PostMapping
    public ApiResponse<Note> create(HttpServletRequest request, @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        Long courseId = Long.valueOf(String.valueOf(body.get("courseId")));
        return ApiResponse.ok(noteService.create(u.id(), courseId,
                body.get("kpName"), body.get("title"), body.get("content")));
    }

    @PutMapping("/{id}")
    public ApiResponse<Note> update(HttpServletRequest request, @PathVariable Long id,
                                    @RequestBody Map<String, String> body) {
        AuthUser u = CurrentUser.get(request);
        return ApiResponse.ok(noteService.update(u.id(), id,
                body.get("kpName"), body.get("title"), body.get("content")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        AuthUser u = CurrentUser.get(request);
        noteService.delete(u.id(), id);
        return ApiResponse.ok(null);
    }
}
