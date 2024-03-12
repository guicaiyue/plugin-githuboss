(function () {
    // 立即执行函数开始
    const githubFastHttpsKey = "githuboss_githubFastHttps";
    const imgRegex = /(http.*jsdelivr.net)/g;
    const testFilePath = "/gh/guicaiyue/FigureBed/Pwa/favicon.ico";
    const domains = [
        "https://gcore.jsdelivr.net",
        "https://cdn.jsdelivr.net",
        "https://fastly.jsdelivr.net",
        "https://originfastly.jsdelivr.net",
        "https://quantil.jsdelivr.net",
    ];

    // 获取本地缓存的值
    const getLocalValue = () => localStorage.getItem(githubFastHttpsKey);

    // 设置本地缓存的值
    const setLocalValue = (value) => localStorage.setItem(githubFastHttpsKey, value);

    // 根据浏览器设置的语言判断用户是否在中国
    const isInChina = () => {
        const chinaRegexp = /\s*zh-(CN|cn)\s*/g;
        return chinaRegexp.test(navigator.language || navigator.browserLanguage || navigator.userLanguage);
    };

    // 获取图片的指定属性地址
    const getImageSrc = (imgElement,attribute) => imgElement.getAttribute(attribute);

    // 替换图片的指定属性地址
    const replaceImageSrc = (imgElement,attribute, domain) => {
        const src = getImageSrc(imgElement,attribute);
        if (src && src.match(/^http.*jsdelivr\.net/i)) {
            imgElement.setAttribute(attribute, replaceImageProtocolAndDomain(src, domain));
        }
    }

    // 测试哪个域名速度最快
    const testDomainSpeed = async (domain) => {
        try {
            const startTime = performance.now();
            const url = `${domain}${testFilePath}`;
            const response = await fetch(url, {mode: "no-cors"});
            const endTime = performance.now();
            return endTime - startTime;
        }catch (e) {
            return 9999999;
        }
    };

    // 获取最快的域名
    const getFastestDomain = async (domains) => {
        const domainSpeeds = await Promise.all(domains.map((domain) => testDomainSpeed(domain)));
        const minSpeed = Math.min(...domainSpeeds);
        const index = domainSpeeds.indexOf(minSpeed);
        return domains[index];
    };

    // 替换图片地址的协议和域名
    const replaceImageProtocolAndDomain = (imgSrc, domain) => {
        return imgSrc.replace(imgRegex, domain);
    };

    // 初始化操作
    const init = () => {
        // 第一步，从本地缓存中获取 githubFastHttps 的值，如果不存在，则根据用户所在地区设置不同的值，并将其设置到本地缓存中
        let githubFastHttps = getLocalValue();
        let domain = domains.indexOf(githubFastHttps) !== -1 ? githubFastHttps : isInChina()? "https://gcore.jsdelivr.net":"https://cdn.jsdelivr.net";

        // 第二步，替换页面中所有http.*jsdelivr.net的图片路径
        let imgList = document.getElementsByTagName('img');
        for (let i = 0; i < imgList.length; i++) {
            replaceImageSrc(imgList[i],"src",domain);
            replaceImageSrc(imgList[i],"data-src",domain);
        }
        // 第三步 异步更新最快的访问地址
        getFastestDomain(domains).then(r => setLocalValue(r));
    };
    document.addEventListener("DOMContentLoaded",  function () {
        init();
    });
})();