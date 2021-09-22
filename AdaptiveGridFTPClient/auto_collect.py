import os

#src = "gsiftp://oasis-dm.sdsc.xsede.org//oasis/scratch-comet/earslan/temp_project/1G-"
base_format = "timeout 65 mvn exec:java -Dexec.args=\"src/main/resources/config.cfg -s {0} -d {1} -maxcc {2}\""
src_format = "gsiftp://gridftp.stampede2.tacc.xsede.org:2811/scratch/06979/mazaman/data/{0}/"
dest_format = "gsiftp://oasis-dm.sdsc.xsede.org/expanse/lustre/scratch/mazaman/temp_project/data/"
ccs = [2,4,8,16,32]
filesizes = ["large", "medium", "small"]

count = 0
while True:
    count += 1 
    for cc in ccs:
        for filesize in filesizes:
            src = src_format.format(filesize)
            dest = dest_format
            command = base_format.format(src,dest,cc)
            print(count, command)
            data = os.popen(command).read()
            rename = "mv inst-throughput.txt data/inst-throughput-cc-{0}-fs-{1}-iter-{2}.txt".format(cc, filesize, count)
            data = os.popen(rename).read()

