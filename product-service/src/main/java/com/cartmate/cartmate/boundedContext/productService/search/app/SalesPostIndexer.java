package com.cartmate.cartmate.boundedContext.productService.search.app;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.cartmate.cartmate.boundedContext.productService.post.domain.SalesPost;
import com.cartmate.cartmate.boundedContext.productService.post.out.SalesPostRepository;
import com.cartmate.cartmate.boundedContext.productService.product.domain.Category;
import com.cartmate.cartmate.boundedContext.productService.product.out.CategoryRepository;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostDocument;
import com.cartmate.cartmate.boundedContext.productService.search.domain.SalesPostDocumentFactory;
import com.cartmate.cartmate.boundedContext.productService.search.out.SalesPostIndexAdmin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB의 최신 판매글로 검색 문서를 덮어쓰거나 지운다. */
@Service
@RequiredArgsConstructor
public class SalesPostIndexer {

    private final ElasticsearchClient client;
    private final SalesPostIndexAdmin indexAdmin;
    private final SalesPostRepository salesPostRepository;
    private final CategoryRepository categoryRepository;

    /** 판매글 하나를 모든 쓰기 대상 색인에 반영한다. 검색에 나오면 안 되는 판매글이면 문서를 지운다. */
    @Transactional(readOnly = true)
    public void sync(Long postId) {
        Optional<SalesPostDocument> document = salesPostRepository.findById(postId)
                .flatMap(post -> SalesPostDocumentFactory.create(post, categoryNames()));
        String id = String.valueOf(postId);
        try {
            for (String index : indexAdmin.writeTargets()) {
                if (document.isPresent()) {
                    client.index(i -> i.index(index).id(id).document(document.get()));
                } else {
                    // 없는 문서를 지우면 not_found 결과만 오고 예외는 나지 않는다
                    client.delete(d -> d.index(index).id(id));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 재색인용. 판매글들을 한 번에 읽어 지정한 색인에 넣는다. 문서가 없는 판매글은 건너뛴다. */
    @Transactional(readOnly = true)
    public void bulkIndex(String index, List<Long> postIds) {
        Map<Long, String> categoryNames = categoryNames();
        List<SalesPostDocument> documents = salesPostRepository.findWithProductsByIdIn(postIds).stream()
                .map(post -> SalesPostDocumentFactory.create(post, categoryNames))
                .flatMap(Optional::stream)
                .toList();
        if (documents.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(index).id(String.valueOf(doc.postId())).document(doc))));
        try {
            BulkResponse response = client.bulk(bulk.build());
            if (response.errors()) {
                throw new IllegalStateException("판매글 검색 색인 일괄 반영 중 실패한 문서가 있습니다: " + index);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<Long, String> categoryNames() {
        return categoryRepository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }
}
