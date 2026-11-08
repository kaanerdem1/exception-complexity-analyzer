package com.scanner.exception.repo;

import com.scanner.exception.config.Config;
import com.scanner.exception.model.RepoInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * source=git için verilen adreslerden repo listesi oluşturur. GitHub, GitLab veya herhangi bir git sunucusu olabilir:
 *   https://github.com/sahip/repo
 *   https://github.com/sahip/repo.git
 *   https://github.com/sahip/repo/tree/develop   (dal adresten alınır)
 *   git@github.com:sahip/repo.git
 */
public final class GitUrlRepos {

    private static final Pattern HTTP = Pattern.compile("^(https?://[^/]+)/(.+?)/([^/]+?)(\\.git)?(/(-/)?tree/(.+))?/?$");
    private static final Pattern SSH = Pattern.compile("^(?:ssh://)?[^@]+@([^:/]+)[:/](.+?)/([^/]+?)(\\.git)?/?$");

    private GitUrlRepos() { }

    public static List<RepoInfo> list(Config cfg) {
        List<RepoInfo> repos = new ArrayList<RepoInfo>();
        for (String raw : cfg.gitUrls) repos.add(parse(raw.trim()));
        return repos;
    }

    static RepoInfo parse(String url) {
        Matcher m = HTTP.matcher(url);
        if (m.matches()) {
            String base = m.group(1);
            String owner = m.group(2);
            String name = m.group(3);
            String web = base + "/" + owner + "/" + name;
            RepoInfo r = new RepoInfo(Config.Source.GIT, owner, owner, name, name, web + ".git", web);
            if (m.group(7) != null) r.requestedBranch = m.group(7);
            return r;
        }
        m = SSH.matcher(url);
        if (m.matches()) {
            String host = m.group(1);
            String owner = m.group(2);
            String name = m.group(3);
            return new RepoInfo(Config.Source.GIT, owner, owner, name, name, url, "https://" + host + "/" + owner + "/" + name);
        }
        String name = url.replaceAll("\\.git/?$", "").replaceAll(".*[/:]", "");
        return new RepoInfo(Config.Source.GIT, "GIT", "GIT", name, name, url, null);
    }
}
