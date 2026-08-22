package com.aihub.community.controller;

import com.aihub.common.auth.RequireLogin;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;
import com.aihub.common.result.Result;
import com.aihub.common.trace.UserContext;
import com.aihub.community.dto.CreateCommentRequest;
import com.aihub.community.dto.CreatePostRequest;
import com.aihub.community.service.PostCommentService;
import com.aihub.community.service.PostFavoriteService;
import com.aihub.community.service.PostService;
import com.aihub.community.vo.PostCommentVo;
import com.aihub.community.vo.PostVo;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口（含评论与收藏）。读取匿名可访问，写入需登录。
 */
@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private final PostService postService;
    private final PostCommentService commentService;
    private final PostFavoriteService favoriteService;

    public PostController(PostService postService,
                          PostCommentService commentService,
                          PostFavoriteService favoriteService) {
        this.postService = postService;
        this.commentService = commentService;
        this.favoriteService = favoriteService;
    }

    @PostMapping
    @RequireLogin
    public Result<Long> create(@Valid @RequestBody CreatePostRequest request) {
        return Result.ok(postService.create(UserContext.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<PostVo> detail(@PathVariable Long id) {
        return Result.ok(postService.detail(id));
    }

    /** 分页列表，可按 type 筛选（EXPERIENCE / GUIDE / QUESTION）。 */
    @GetMapping
    public Result<PageResult<PostVo>> list(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return Result.ok(postService.list(type, new PageQuery(page, size)));
    }

    /* ------------------------------------------------------------ 评论 */

    @PostMapping("/{id}/comments")
    @RequireLogin
    public Result<Long> comment(@PathVariable Long id, @Valid @RequestBody CreateCommentRequest request) {
        return Result.ok(commentService.create(id, UserContext.getUserId(), request));
    }

    /** 评论按时间升序返回，符合对话阅读顺序。 */
    @GetMapping("/{id}/comments")
    public Result<PageResult<PostCommentVo>> comments(@PathVariable Long id,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return Result.ok(commentService.listByPost(id, new PageQuery(page, size)));
    }

    /* ------------------------------------------------------------ 收藏 */

    @PostMapping("/{id}/favorite")
    @RequireLogin
    public Result<Boolean> favorite(@PathVariable Long id) {
        return Result.ok(favoriteService.favorite(id, UserContext.getUserId()));
    }

    @DeleteMapping("/{id}/favorite")
    @RequireLogin
    public Result<Boolean> unfavorite(@PathVariable Long id) {
        return Result.ok(favoriteService.unfavorite(id, UserContext.getUserId()));
    }

    @GetMapping("/{id}/favorite")
    public Result<Boolean> favorited(@PathVariable Long id) {
        return Result.ok(favoriteService.hasFavorited(id, UserContext.getUserId()));
    }
}
