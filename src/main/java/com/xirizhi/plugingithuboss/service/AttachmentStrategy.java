package com.xirizhi.plugingithuboss.service;

/**
 * 附件策略接口：抽象上传/删除等操作，便于后续对接 Halo 核心附件扩展点。
 * 通过策略模式解耦控制器与具体实现，提升可维护性。
 */
public interface AttachmentStrategy {

    /**
     * 上传结果数据结构。
     */
    class UploadResult {
        // 生成的附件记录名称（AttachmentRecord.metadata.name）
        private final String name;
        // 生成的 CDN 访问 URL
        private final String cdnUrl;
        // GitHub 返回的内容 SHA
        private final String sha;
        // 在仓库中的相对路径
        private final String path;

        public UploadResult(String name, String cdnUrl, String sha, String path) {
            this.name = name;
            this.cdnUrl = cdnUrl;
            this.sha = sha;
            this.path = path;
        }
        public String getName() { return name; }
        public String getCdnUrl() { return cdnUrl; }
        public String getSha() { return sha; }
        public String getPath() { return path; }
    }

    /**
     * 删除结果数据结构。
     */
    class DeleteResult {
        // 附件记录名称
        private final String name;
        // 是否已删除（逻辑删除）
        private final boolean deleted;
        // 是否执行了远程删除（GitHub）
        private final boolean remote;

        public DeleteResult(String name, boolean deleted, boolean remote) {
            this.name = name;
            this.deleted = deleted;
            this.remote = remote;
        }
        public String getName() { return name; }
        public boolean isDeleted() { return deleted; }
        public boolean isRemote() { return remote; }
    }

    /**
     * 上传附件。
     * @param repoName 仓库配置的 metadata.name
     * @param originalFilename 原始文件名（用于提取扩展名）
     * @param data 文件二进制数据
     * @param size 文件大小（字节）
     * @return 上传结果
     */
    UploadResult upload(String repoName, String originalFilename, byte[] data, long size) throws Exception;

    /**
     * 删除附件。
     * @param attachmentRecordName 附件记录名称（AttachmentRecord.metadata.name）
     * @return 删除结果，包含是否远程删除
     */
    DeleteResult delete(String attachmentRecordName) throws Exception;
}