# Change Log

## 0.2.0 - 2022-12-09
### Added
- Better error handling: Now when a node throws an exception, this exception is wrapped in an ex-info that contains `:paths` in ex-data, with paths being a set of vectors. Each vector starts with a node label, followed by sources labels that go `upwards` from the labelled node until the failing node.

### Changed
- `parallel-processor` now uses the protocol fn -compute instead of the `:handler` (implementation detail of base compute-node).

## 0.1.1 - 2022-09-30

### Changed
- Revamped documentation. No code changes.
## 0.1.0 - 2022-08-03

### Added
- Support for ClojureScript

## 0.0.1 - 2022-07-23

### Added
- Initial release