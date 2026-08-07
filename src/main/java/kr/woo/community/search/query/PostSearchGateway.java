package kr.woo.community.search.query;

import java.util.List;

public interface PostSearchGateway {

    List<PostSearchCandidate> search(PostSearchCriteria criteria);

    PostSearchPage searchPage(PostSearchCriteria criteria, String cursor);
}
