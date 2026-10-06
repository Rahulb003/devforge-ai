package com.devforge.ai.documentationservice.repository;

import com.devforge.ai.documentationservice.entity.DocumentEntity;
import com.devforge.ai.documentationservice.generate.DocumentKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Documents, always scoped by their set.
 *
 * <p>Scoping by set makes the project check transitive: the set was loaded with its project id, so
 * a document id from another tenant cannot resolve.
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

  List<DocumentEntity> findByDocSetIdOrderByKindAsc(UUID docSetId);

  Optional<DocumentEntity> findByDocSetIdAndKind(UUID docSetId, DocumentKind kind);
}
