package run.halo.oss;

import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * @author xirizhi
 */
@Data
public class GithubOssProperties {
    private String owner;
    private String repo;
    private String branch;
    private String path;
    private String token;
    private String creatName;
    private String jsdelivr;
    private Boolean deleteSync;

    public String getObjectName(String filename) {
        var objectName = filename;
        if (StringUtils.hasText(getPath())) {
            objectName = getPath() + "/" + objectName;
        }
        return objectName;
    }
}
