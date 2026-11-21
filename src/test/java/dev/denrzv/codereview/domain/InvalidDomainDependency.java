package dev.denrzv.codereview.domain;

import org.springframework.http.HttpStatus;

record InvalidDomainDependency(HttpStatus status) {
}
