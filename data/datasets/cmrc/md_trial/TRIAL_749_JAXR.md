# JAXR

JAXR（Java API for XML Registries 简称JAXR）是为Java平台上的应用程序定义的API，用以访问不同种类的元数据注册中心并进行交互。JAXR API是在JCP下开发的，代号JSR 93。JAXR提供了一种统一和标准的Java API，用于访问不同类型的基于XML的元数据注册中心。JAXR目前实现支持ebXML Registry2.0版和UDDI2.0版。未来可以支持更多的类似的注册中心。JAXR为客户端提供了API，与XML注册中心进行交互，同时为服务中心提供者提供了服务提供者接口（SPI），这样，注册中心的实现是可插拔的。JAXR API将应用代码与下层的注册中心机制相隔离。当编写基于JAXR的客户端浏览和操作注册中心时，当注册中心更换时，比如从UDDI更换为ebXML，代码不需要进行修改。
