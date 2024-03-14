package run.halo.oss.enums;

public enum GithubUrlEnum {
    API_TREE("查询github指定路径目录下所有文件信息",
        "https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}"),
    API_CONTENTS("github指定文件路径信息,根据请求get,put,delete类型决定是上传、删除、还是查询",
        "https://api.github.com/repos/{owner}/{repo}/contents/{path}");
    private String desc;
    private String url;

    GithubUrlEnum(String desc, String url) {
        this.desc = desc;
        this.url = url;
    }

    public String getDesc() {
        return desc;
    }

    public String getUrl() {
        return url;
    }
}
