# Contributing to Spring N+1 Detector

Thank you for considering contributing! Here's how you can help.

## 🐛 Bug Reports

1. Search existing issues first
2. Include: Spring Boot version, Java version, and a minimal reproduction
3. Include the full console output from the detector

## 💡 Feature Requests

Open an issue with the `enhancement` label and describe:
- What problem does this solve?
- What would the API look like?
- Are there any alternatives?

## 🔧 Pull Requests

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Write tests for your changes
4. Run the full test suite: `mvn clean verify`
5. Submit a PR with a clear description

## 🏗️ Development Setup

```bash
# Clone
git clone https://github.com/nour/spring-nplus1-detector.git
cd spring-nplus1-detector

# Build
mvn clean install

# Run tests
mvn test
```

### Requirements
- Java 17+
- Maven 3.9+

## 📐 Code Style

- Follow existing code patterns
- Use meaningful Javadoc on public APIs
- Keep dependencies minimal (especially in `core`)
- Write tests for new functionality

## 📄 License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
